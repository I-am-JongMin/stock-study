package com.stockstudy.marketdata.instrument

import com.stockstudy.domain.text.Chosung
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.LocalDate

/**
 * 종목마스터 저장소 (§F1.1).
 *
 * **왜 배치 쓰기만 [NamedParameterJdbcTemplate] 인가** — 읽기는 집 스타일대로
 * [JdbcClient] 로 쓴다 (ADR-004). 다만 JdbcClient 에는 배치 API 가 없어서, 4,300 행을
 * 한 줄씩 보내면 왕복이 4,300 번이다. 배치가 필요한 한 군데만 아래 API 를 쓴다.
 */
class InstrumentRepository(
    private val jdbc: JdbcClient,
    private val batchJdbc: NamedParameterJdbcTemplate,
) {

    /** 해당 시장에서 아직 상장 중인 종목코드. 상장폐지 후보를 구하는 기준이 된다. */
    fun activeCodes(market: MarketType): Set<String> =
        jdbc.sql("SELECT code FROM instrument WHERE market = :market AND delisted_at IS NULL")
            .param("market", market.name)
            .query(String::class.java)
            .list()
            .toSet()

    /**
     * 멱등 upsert. **같은 입력을 두 번 넣어도 두 번째는 아무 행도 건드리지 않는다** (§F1 AC).
     *
     * `DO UPDATE ... WHERE` 가 그 핵심이다. 조건 없이 UPDATE 하면 매 실행마다 4,300 행을
     * 다시 쓰고 `updated_at` 이 전부 바뀐다. 그러면 "무엇이 실제로 변했는가"를 영영 알 수
     * 없고, 변경 감지를 거기에 기대는 코드가 나중에 생기면 조용히 망가진다.
     *
     * `listed_at` 에 `COALESCE` 를 쓰는 이유 — 키움이 `regDay` 를 빈 값으로 주는 종목이
     * 있다. 이미 알고 있던 상장일을 빈 값으로 덮어쓰지 않는다.
     *
     * `delisted_at = NULL` 은 **재상장 복구**다. 상장폐지로 표시했던 종목이 응답에 다시
     * 나타나면 되살린다 — 거래정지가 풀렸거나 우리가 잘못 표시했던 경우다.
     *
     * @return 실제로 삽입되거나 변경된 행 수
     */
    fun upsertAll(instruments: List<Instrument>): Int {
        if (instruments.isEmpty()) return 0

        return instruments.chunked(BATCH_SIZE).sumOf { chunk ->
            val params = chunk.map { instrument ->
                MapSqlParameterSource()
                    .addValue("code", instrument.code)
                    .addValue("market", instrument.market.name)
                    .addValue("securityType", instrument.securityType.name)
                    .addValue("name", instrument.name)
                    // 검색어도 같은 함수를 지난다. 한쪽만 정규화하면 조용히 안 맞는다 (ADR-017)
                    .addValue("nameChosung", Chosung.of(instrument.name))
                    .addValue("listedAt", instrument.listedAt)
            }.toTypedArray()

            batchJdbc.batchUpdate(UPSERT_SQL, params).sum()
        }
    }

    /** 상장폐지로 표시한다. 행을 지우지 않는다 — 과거 주문이 참조한다 (§F1.3). */
    fun markDelisted(codes: Collection<String>, delistedAt: LocalDate): Int {
        if (codes.isEmpty()) return 0

        return codes.chunked(BATCH_SIZE).sumOf { chunk ->
            jdbc.sql(
                """
                UPDATE instrument
                   SET delisted_at = :delistedAt, updated_at = now()
                 WHERE code IN (:codes)
                   AND delisted_at IS NULL
                """.trimIndent(),
            )
                .param("delistedAt", delistedAt)
                .param("codes", chunk)
                .update()
        }
    }

    private companion object {
        /** 한 번에 보낼 행 수. 너무 크면 statement 가 비대해지고 너무 작으면 왕복이 는다. */
        const val BATCH_SIZE = 500

        val UPSERT_SQL = """
            INSERT INTO instrument (code, market, security_type, name, name_chosung, currency, listed_at)
            VALUES (:code, :market, :securityType, :name, :nameChosung, 'KRW', :listedAt)
            ON CONFLICT (code) DO UPDATE SET
                market        = EXCLUDED.market,
                security_type = EXCLUDED.security_type,
                name          = EXCLUDED.name,
                name_chosung  = EXCLUDED.name_chosung,
                listed_at     = COALESCE(EXCLUDED.listed_at, instrument.listed_at),
                delisted_at   = NULL,
                updated_at    = now()
            WHERE instrument.market        IS DISTINCT FROM EXCLUDED.market
               OR instrument.security_type IS DISTINCT FROM EXCLUDED.security_type
               OR instrument.name          IS DISTINCT FROM EXCLUDED.name
               OR instrument.listed_at     IS DISTINCT FROM COALESCE(EXCLUDED.listed_at, instrument.listed_at)
               OR instrument.delisted_at   IS NOT NULL
        """.trimIndent()
    }
}
