package com.stockstudy.marketdata.instrument

import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** 종목 목록을 주는 곳. 동기화 로직이 키움을 직접 알 필요는 없다 (V6 가 가짜로 바꾼다). */
fun interface InstrumentSource {
    fun instrumentList(market: MarketType): List<Instrument>
}

/**
 * 종목마스터 동기화 (§F1.1, ADR-026).
 *
 * **멱등성이 완료 조건이다** — 두 번 연달아 실행해도 종목 수가 늘지 않는다. 그 보장은
 * `ON CONFLICT DO UPDATE` 가 만든다 ([InstrumentRepository.upsertAll]).
 *
 * **진짜 어려운 부분은 삽입이 아니라 삭제다.** 응답에 없는 종목을 상장폐지로 표시해야
 * 하는데, 외부 API 가 어떤 이유로든 일부만 돌려주면 **멀쩡한 종목이 통째로 상장폐지된다.**
 * 그리고 그 사고는 조용하다 — 에러도 안 나고, 다음 실행에서 되살아나므로 로그를 봐도
 * 알기 어렵다. 그래서 상장폐지는 **의심스러우면 하지 않는다**:
 *
 * 1. 수신 건수가 [InstrumentSyncProperties.minInstrumentCount] 미만이면 중단
 * 2. 상장폐지 후보가 기존 활성 종목의 [InstrumentSyncProperties.maxDelistRatio] 를
 *    넘으면 **아무것도 바꾸지 않고** 실패한다. 사람이 봐야 한다
 *
 * 둘 다 "조용히 틀리느니 시끄럽게 멈춘다"는 같은 원칙이다 (ADR-009 와 같은 사상).
 */
class InstrumentSyncService(
    private val source: InstrumentSource,
    private val repository: InstrumentRepository,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val properties: InstrumentSyncProperties,
) {

    fun syncAll(): List<MarketSyncResult> = MarketType.entries.map(::sync)

    /**
     * 시장 하나를 동기화한다.
     *
     * 트랜잭션이 시장 단위인 이유 — 코스피와 코스닥은 서로 독립이다. 한쪽이 실패했다고
     * 다른 쪽의 성공을 되돌릴 이유가 없고, 반대로 한 시장 안에서 upsert 만 되고 상장폐지가
     * 빠지면 마스터가 어중간한 상태로 남는다.
     *
     * **`@Transactional` 대신 [TransactionTemplate] 을 쓰는 이유** — 애너테이션은 프록시로
     * 동작하므로 `syncAll()` 이 같은 객체의 `sync()` 를 부르면 **프록시를 지나지 않아
     * 트랜잭션이 통째로 사라진다.** 조용히 사라지는 것이 이 함정의 특징이다. 경계를
     * 코드로 적어두면 자기호출이든 아니든 같게 동작하고, 어디서 열리고 닫히는지가 눈에 보인다.
     * (Kotlin 클래스가 기본 final 이라 프록시 생성 자체가 실패한 것은 덤이었다.)
     */
    fun sync(market: MarketType): MarketSyncResult =
        requireNotNull(transactions.execute { syncWithinTransaction(market) })

    private fun syncWithinTransaction(market: MarketType): MarketSyncResult {
        val received = source.instrumentList(market)

        if (received.size < properties.minInstrumentCount) {
            throw SuspiciousInstrumentSyncException(
                "$market 수신 종목이 ${received.size}건입니다. " +
                    "최소 기대치(${properties.minInstrumentCount}건)에 못 미쳐 동기화를 중단합니다 — " +
                    "부분 응답으로 마스터를 망가뜨리지 않습니다.",
            )
        }

        val instruments = received.distinctBy { it.code }
        if (instruments.size != received.size) {
            log.warn("$market 응답에 중복 종목코드가 ${received.size - instruments.size}건 있어 제거했습니다")
        }

        val activeBefore = repository.activeCodes(market)
        val missing = activeBefore - instruments.map { it.code }.toSet()
        guardAgainstMassDelisting(market, activeBefore, missing)

        val changed = repository.upsertAll(instruments)
        val delisted = repository.markDelisted(missing, today())

        return MarketSyncResult(market, instruments.size, changed, delisted).also {
            log.info(
                "{} 종목마스터 동기화 — 수신 {}건, 변경 {}건, 상장폐지 {}건",
                market, it.received, it.changed, it.delisted,
            )
        }
    }

    private fun guardAgainstMassDelisting(
        market: MarketType,
        activeBefore: Set<String>,
        missing: Set<String>,
    ) {
        // 첫 실행은 기준이 없다. 비어 있는 상태에서 지울 것도 없다.
        if (activeBefore.isEmpty() || missing.isEmpty()) return

        val ratio = missing.size.toDouble() / activeBefore.size
        if (ratio > properties.maxDelistRatio) {
            throw SuspiciousInstrumentSyncException(
                "$market 상장폐지 후보가 ${missing.size}건(기존 활성 ${activeBefore.size}건의 " +
                    "${"%.1f".format(ratio * 100)}%)으로 한도(${"%.1f".format(properties.maxDelistRatio * 100)}%)를 " +
                    "넘었습니다. 아무것도 바꾸지 않고 중단합니다 — 외부 응답이 불완전할 가능성이 큽니다.",
            )
        }
    }

    /**
     * 상장폐지일은 **한국 날짜**다 (§09).
     *
     * 저장·로깅은 UTC 지만 "상장폐지된 날"은 한국거래소의 달력 위에 있다. UTC 로 계산하면
     * 한국 시각 오전 9시 이전 실행이 전날로 기록된다. 그리고 키움은 상장폐지일을 주지
     * 않으므로 이 값은 **"우리가 사라진 것을 발견한 날"** 이다 — 실제 상장폐지일과 다를 수 있다.
     */
    private fun today(): LocalDate = LocalDate.now(clock.withZone(KOREA))

    private companion object {
        val KOREA: ZoneId = ZoneId.of("Asia/Seoul")
        val log = LoggerFactory.getLogger(InstrumentSyncService::class.java)
    }
}

data class MarketSyncResult(
    val market: MarketType,
    val received: Int,
    /** 실제로 삽입되거나 내용이 바뀐 행 수. 두 번째 실행에서는 0 이어야 한다. */
    val changed: Int,
    val delisted: Int,
)

/** 외부 응답이 믿을 만하지 않아 동기화를 중단했다. 트랜잭션은 롤백된다. */
class SuspiciousInstrumentSyncException(message: String) : RuntimeException(message)

/**
 * 동기화 안전장치 설정 (ADR-026).
 *
 * 기본값의 근거 — 유가증권 2,496 / 코스닥 1,837 건이므로 100 건 미만은 명백한 이상이다.
 * 상장폐지는 하루에 많아야 몇 건이라 5% (약 100 건)면 충분히 느슨하면서도 사고는 잡는다.
 */
class InstrumentSyncProperties(
    val minInstrumentCount: Int = 100,
    val maxDelistRatio: Double = 0.05,
) {
    init {
        require(maxDelistRatio in 0.0..1.0) { "maxDelistRatio 는 0.0~1.0 이어야 합니다: $maxDelistRatio" }
    }
}
