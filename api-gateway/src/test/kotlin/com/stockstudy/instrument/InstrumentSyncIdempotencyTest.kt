package com.stockstudy.instrument

import com.stockstudy.domain.text.Chosung
import com.stockstudy.marketdata.instrument.Instrument
import com.stockstudy.marketdata.instrument.InstrumentSource
import com.stockstudy.marketdata.instrument.InstrumentSyncService
import com.stockstudy.marketdata.instrument.MarketType
import com.stockstudy.marketdata.instrument.SecurityType
import com.stockstudy.marketdata.instrument.SuspiciousInstrumentSyncException
import com.stockstudy.support.IntegrationTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import java.time.LocalDate

/**
 * 하네스 V6 — 종목마스터 동기화 (§F1 AC, ADR-026).
 *
 * **완료 조건이 그대로 테스트다** — "동기화를 두 번 연달아 실행해도 종목 수가 늘지 않는다".
 * 다만 진짜 위험한 것은 삽입이 아니라 삭제라서, 절반은 상장폐지 쪽을 본다.
 *
 * 키움은 [InstrumentSource] 가짜로 바꾼다. 여기서 증명할 것은 SQL 과 안전장치이지
 * HTTP 가 아니다 (그쪽은 V4 가 본다). DB 는 진짜 PostgreSQL 이어야 한다 —
 * `ON CONFLICT DO UPDATE ... WHERE` 의 동작은 mock 으로 증명되지 않는다.
 */
@Import(InstrumentSyncIdempotencyTest.FakeSourceConfig::class)
@TestPropertySource(properties = ["instrument-sync.min-instrument-count=3"])
@DisplayName("V6 — 종목마스터 동기화")
class InstrumentSyncIdempotencyTest(
    @Autowired private val sync: InstrumentSyncService,
    @Autowired private val source: MutableInstrumentSource,
    @Autowired private val jdbc: JdbcClient,
) : IntegrationTestBase() {

    /**
     * **코스닥으로 격리한다.** 다른 통합 테스트가 KOSPI 종목(005930)을 넣고 주문까지
     * 걸어두기 때문에, 전체 스위트에서는 그 행을 지울 수도(외래키) 무시할 수도
     * (상장폐지 후보로 잡힌다) 없다. 시장 구분이 곧 테스트 격리 경계가 된다 —
     * 여기서 증명할 내용은 어느 시장이든 같다.
     */
    @BeforeEach
    fun clean() {
        jdbc.sql("DELETE FROM instrument WHERE market = :market")
            .param("market", MARKET.name).update()
        source.reset(sampleInstruments(COUNT))
    }

    // ── 완료 조건 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("두 번 연달아 실행해도 종목 수가 늘지 않는다 (§F1 AC)")
    fun syncIsIdempotent() {
        val first = sync.sync(MARKET)
        val second = sync.sync(MARKET)

        first.changed shouldBe COUNT
        countInstruments() shouldBe COUNT

        // 두 번째 실행은 **아무 행도 건드리지 않는다.** 조건 없는 UPDATE 였다면 25 가 나온다.
        second.changed shouldBe 0
        countInstruments() shouldBe COUNT
    }

    @Test
    @DisplayName("두 번째 실행은 updated_at 도 건드리지 않는다 — 변경 감지가 살아 있어야 한다")
    fun unchangedRowsKeepTheirTimestamp() {
        sync.sync(MARKET)
        val before = updatedAtOf("T00001")

        sync.sync(MARKET)

        updatedAtOf("T00001") shouldBe before
    }

    @Test
    @DisplayName("종목명이 바뀌면 초성도 함께 바뀐다 — 한쪽만 갱신되면 검색이 조용히 어긋난다")
    fun renameUpdatesChosungToo() {
        sync.sync(MARKET)
        source.rename("T00001", "에코프로비엠")

        val result = sync.sync(MARKET)

        result.changed shouldBe 1
        nameChosungOf("T00001") shouldBe Chosung.of("에코프로비엠")
    }

    @Test
    @DisplayName("상품 종류가 그대로 저장된다 — ETF 와 주식이 구분된다")
    fun securityTypeIsPersisted() {
        sync.sync(MARKET)

        securityTypeOf("T00001") shouldBe "STOCK"
        securityTypeOf("T00002") shouldBe "ETF"
    }

    // ── 상장폐지 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("응답에서 사라진 종목은 삭제하지 않고 상장폐지로 표시한다 (§F1.3)")
    fun missingInstrumentIsDelistedNotDeleted() {
        sync.sync(MARKET)
        source.remove("T00001")

        val result = sync.sync(MARKET)

        result.delisted shouldBe 1
        // 과거 주문이 참조하므로 행은 남아 있어야 한다.
        countInstruments() shouldBe COUNT
        delistedAtOf("T00001") shouldBe LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
    }

    @Test
    @DisplayName("상장폐지로 표시된 종목이 다시 나타나면 되살아난다")
    fun reappearingInstrumentIsRestored() {
        sync.sync(MARKET)
        source.remove("T00001")
        sync.sync(MARKET)
        delistedAtOf("T00001") shouldBe LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))

        source.reset(sampleInstruments(COUNT))
        val result = sync.sync(MARKET)

        result.changed shouldBe 1
        delistedAtOf("T00001") shouldBe null
    }

    // ── 안전장치 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("상장폐지 후보가 한도를 넘으면 아무것도 바꾸지 않고 실패한다")
    fun massDelistingIsRefused() {
        sync.sync(MARKET)
        // 25 건 중 5 건 증발 = 20% > 한도 5%
        repeat(5) { source.remove("T%05d".format(it + 1)) }

        shouldThrow<SuspiciousInstrumentSyncException> { sync.sync(MARKET) }

        // 한 건도 상장폐지되지 않았다. 이것이 이 안전장치의 전부다.
        activeCount() shouldBe COUNT
    }

    @Test
    @DisplayName("수신 건수가 최소치 미만이면 부분 응답으로 보고 중단한다")
    fun tooFewInstrumentsAborts() {
        sync.sync(MARKET)
        source.reset(sampleInstruments(2)) // 최소치 3 미만

        shouldThrow<SuspiciousInstrumentSyncException> { sync.sync(MARKET) }

        activeCount() shouldBe COUNT
    }

    @Test
    @DisplayName("첫 실행은 기준이 없으므로 안전장치가 발동하지 않는다")
    fun firstRunIsNotBlocked() {
        val result = sync.sync(MARKET)

        result.received shouldBe COUNT
        result.delisted shouldBe 0
    }

    // ── 조회 헬퍼 ─────────────────────────────────────────────────────────

    // 집계는 반드시 이 테스트의 시장으로 좁힌다. 전체를 세면 다른 통합 테스트가 남긴
    // KOSPI 종목까지 포함되어, 테스트 실행 순서에 따라 결과가 달라진다 (실제로 겪었다).
    private fun countInstruments(): Int =
        jdbc.sql("SELECT count(*) FROM instrument WHERE market = :market")
            .param("market", MARKET.name).query(Int::class.java).single()

    private fun activeCount(): Int =
        jdbc.sql("SELECT count(*) FROM instrument WHERE market = :market AND delisted_at IS NULL")
            .param("market", MARKET.name).query(Int::class.java).single()

    private fun nameChosungOf(code: String): String =
        jdbc.sql("SELECT name_chosung FROM instrument WHERE code = :code")
            .param("code", code).query(String::class.java).single()

    private fun securityTypeOf(code: String): String =
        jdbc.sql("SELECT security_type FROM instrument WHERE code = :code")
            .param("code", code).query(String::class.java).single()

    private fun delistedAtOf(code: String): LocalDate? =
        jdbc.sql("SELECT delisted_at FROM instrument WHERE code = :code")
            .param("code", code).query(LocalDate::class.java).optional().orElse(null)

    private fun updatedAtOf(code: String): java.time.Instant =
        jdbc.sql("SELECT updated_at FROM instrument WHERE code = :code")
            .param("code", code).query(java.time.Instant::class.java).single()

    /** 호출될 때마다 현재 목록을 돌려주는 가짜. 테스트가 목록을 직접 흔든다. */
    class MutableInstrumentSource : InstrumentSource {
        private var byMarket = mutableMapOf<MarketType, MutableList<Instrument>>()

        fun reset(instruments: List<Instrument>) {
            byMarket = MarketType.entries.associateWith { mutableListOf<Instrument>() }.toMutableMap()
            byMarket[MARKET] = instruments.toMutableList()
        }

        fun remove(code: String) {
            byMarket.values.forEach { list -> list.removeIf { it.code == code } }
        }

        fun rename(code: String, name: String) {
            byMarket.forEach { (market, list) ->
                val index = list.indexOfFirst { it.code == code }
                if (index >= 0) list[index] = list[index].copy(name = name)
                byMarket[market] = list
            }
        }

        override fun instrumentList(market: MarketType): List<Instrument> =
            byMarket[market].orEmpty().toList()
    }

    @TestConfiguration
    class FakeSourceConfig {
        /**
         * 실제 빈([com.stockstudy.marketdata.kiwoom.KiwoomMarketDataApi])도 같은 타입이므로
         * `@Primary` 로 이쪽을 고르게 한다. 동기화 서비스는 진짜 빈을 그대로 쓴다 —
         * `@Transactional` 이 실제로 걸리는지까지 이 테스트가 지난다.
         */
        @Bean
        @Primary
        fun fakeInstrumentSource() = MutableInstrumentSource()
    }

    private companion object {
        val MARKET = MarketType.KOSDAQ
        const val COUNT = 25

        fun sampleInstruments(count: Int): List<Instrument> = (1..count).map { index ->
            Instrument(
                // 다른 테스트의 KOSPI 종목코드와 겹치지 않는 대역을 쓴다.
                code = "T%05d".format(index),
                market = MARKET,
                securityType = if (index == 2) SecurityType.ETF else SecurityType.STOCK,
                name = "테스트종목$index",
                listedAt = LocalDate.of(2020, 1, 1),
            )
        }
    }
}
