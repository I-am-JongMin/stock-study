package com.stockstudy

import com.stockstudy.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 0 의 진짜 완료 조건.
 *
 * "앱이 뜬다"가 아니라 **"§03 의 불변식이 DB 레벨에서 실제로 막힌다"** 를 증명한다.
 * 이 테스트들이 통과하면, Phase 3 에서 동시성 코드를 잘못 쓰더라도 데이터가
 * 조용히 망가지는 대신 트랜잭션이 시끄럽게 실패한다.
 *
 * `@Transactional` 을 붙이지 않은 것은 의도적이다. 제약 위반이 발생하면 Postgres 는
 * 해당 트랜잭션을 중단시키므로, 하나의 트랜잭션 안에서 "위반시키고 → 다시 조회"를
 * 할 수 없다. 문장마다 자동 커밋되게 두면 각 검증이 서로 간섭하지 않는다.
 */
class BaselineSchemaTest @Autowired constructor(
    private val jdbc: JdbcClient,
) : IntegrationTestBase() {

    companion object {
        /** 테스트 간 UNIQUE 충돌을 피하기 위한 순번. */
        private val seq = AtomicLong(System.nanoTime())
    }

    private fun nextUserId(): Long = seq.incrementAndGet()

    private fun createAccount(cash: Long = 1_000_000): Long =
        jdbc.sql(
            """
            INSERT INTO account (user_id, available_cash)
            VALUES (:userId, :cash)
            RETURNING id
            """.trimIndent()
        )
            .param("userId", nextUserId())
            .param("cash", cash)
            .query(Long::class.java)
            .single()

    private fun ensureInstrument(code: String = "005930"): String {
        jdbc.sql(
            """
            INSERT INTO instrument (code, market, name)
            VALUES (:code, 'KOSPI', '삼성전자')
            ON CONFLICT (code) DO NOTHING
            """.trimIndent()
        ).param("code", code).update()
        return code
    }

    // ────────────────────────── 마이그레이션 ──────────────────────────

    @Test
    fun `Flyway 가 마이그레이션을 끝까지 적용한다`() {
        val applied = jdbc.sql("SELECT version FROM flyway_schema_history WHERE success = true")
            .query(String::class.java)
            .list()

        assertThat(applied).contains("1", "2")
    }

    @Test
    fun `스펙 5장의 테이블이 모두 생성된다`() {
        val tables = jdbc.sql(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
        ).query(String::class.java).list()

        assertThat(tables).contains(
            "instrument", "trading_calendar", "daily_candle",
            "account", "position", "orders", "execution", "ledger",
            "price_alert", "notification_outbox",
        )
    }

    // ────────────────────────── 불변식 ──────────────────────────

    @Test
    fun `INV-1 예수금은 음수가 될 수 없다`() {
        val accountId = createAccount(cash = 1_000_000)

        // C1 의 조건부 UPDATE 가 뚫렸다고 가정하고 직접 음수를 만들어본다.
        assertThatThrownBy {
            jdbc.sql("UPDATE account SET available_cash = -1 WHERE id = :id")
                .param("id", accountId)
                .update()
        }.isInstanceOf(DataAccessException::class.java)

        val cash = jdbc.sql("SELECT available_cash FROM account WHERE id = :id")
            .param("id", accountId)
            .query(Long::class.java)
            .single()
        assertThat(cash).isEqualTo(1_000_000)
    }

    @Test
    fun `INV-3 보유수량은 음수가 될 수 없다`() {
        val accountId = createAccount()
        val code = ensureInstrument()

        assertThatThrownBy {
            jdbc.sql(
                """
                INSERT INTO position (account_id, code, quantity, total_cost)
                VALUES (:accountId, :code, -1, 0)
                """.trimIndent()
            ).param("accountId", accountId).param("code", code).update()
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `INV-4 원장은 수정도 삭제도 할 수 없다`() {
        val accountId = createAccount()

        jdbc.sql(
            """
            INSERT INTO ledger (account_id, entry_type, amount, balance_after)
            VALUES (:accountId, 'DEPOSIT', 1000000, 1000000)
            """.trimIndent()
        ).param("accountId", accountId).update()

        assertThatThrownBy {
            jdbc.sql("UPDATE ledger SET amount = 999 WHERE account_id = :id")
                .param("id", accountId).update()
        }.hasMessageContaining("append-only")

        assertThatThrownBy {
            jdbc.sql("DELETE FROM ledger WHERE account_id = :id")
                .param("id", accountId).update()
        }.hasMessageContaining("append-only")
    }

    @Test
    fun `INV-4 원장은 TRUNCATE 로도 지울 수 없다`() {
        // V1 의 ledger_append_only 는 FOR EACH ROW 라서 TRUNCATE 에 발화하지 않았다.
        // Phase 0 검증에서 원장이 통째로 지워지는 것을 실제로 확인했고, V2 에서 막았다.
        // 이 테스트가 없으면 누군가 V2 트리거를 지워도 아무도 모른다.
        val accountId = createAccount()

        jdbc.sql(
            """
            INSERT INTO ledger (account_id, entry_type, amount, balance_after)
            VALUES (:accountId, 'DEPOSIT', 1000000, 1000000)
            """.trimIndent()
        ).param("accountId", accountId).update()

        assertThatThrownBy {
            jdbc.sql("TRUNCATE ledger").update()
        }.hasMessageContaining("append-only")

        // 트리거가 트랜잭션을 중단시켰으므로 행은 그대로 남아 있어야 한다.
        val remaining = jdbc.sql("SELECT count(*) FROM ledger WHERE account_id = :id")
            .param("id", accountId)
            .query(Long::class.java)
            .single()
        assertThat(remaining).isEqualTo(1L)
    }

    @Test
    fun `INV-5 체결 수량은 주문 수량을 넘을 수 없다`() {
        val accountId = createAccount()
        val code = ensureInstrument()

        assertThatThrownBy {
            insertOrder(accountId, code, quantity = 10, filledQuantity = 11)
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `INV-6 같은 멱등키로 주문을 두 번 넣을 수 없다`() {
        val accountId = createAccount()
        val code = ensureInstrument()
        val key = "idem-${nextUserId()}"

        insertOrder(accountId, code, idempotencyKey = key)

        // §06 C4 — 애플리케이션이 조회-후-삽입을 하지 않아도 DB 가 막는다.
        assertThatThrownBy {
            insertOrder(accountId, code, idempotencyKey = key)
        }.isInstanceOf(DataAccessException::class.java)

        val count = jdbc.sql(
            "SELECT count(*) FROM orders WHERE account_id = :id AND idempotency_key = :key"
        ).param("id", accountId).param("key", key).query(Long::class.java).single()

        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `INV-7 상태 머신에 없는 주문 상태는 저장되지 않는다`() {
        val accountId = createAccount()
        val code = ensureInstrument()

        assertThatThrownBy {
            insertOrder(accountId, code, status = "SOMEHOW_FILLED")
        }.isInstanceOf(DataAccessException::class.java)
    }

    // ────────────────────────── 도메인 규칙 ──────────────────────────

    @Test
    fun `C6 수량이 0 이면 총매입금액도 0 이어야 한다`() {
        val accountId = createAccount()
        val code = ensureInstrument()

        // 전량 매도 후 total_cost 에 잔여물이 남으면 평균단가가 무한이 된다.
        assertThatThrownBy {
            jdbc.sql(
                """
                INSERT INTO position (account_id, code, quantity, total_cost)
                VALUES (:accountId, :code, 0, 500)
                """.trimIndent()
            ).param("accountId", accountId).param("code", code).update()
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `시장가 주문에는 지정가가 없어야 한다`() {
        val accountId = createAccount()
        val code = ensureInstrument()

        assertThatThrownBy {
            jdbc.sql(
                """
                INSERT INTO orders
                    (account_id, code, side, type, limit_price, quantity, status, idempotency_key)
                VALUES (:accountId, :code, 'BUY', 'MARKET', 70000, 10, 'PENDING', :key)
                """.trimIndent()
            )
                .param("accountId", accountId)
                .param("code", code)
                .param("key", "idem-${nextUserId()}")
                .update()
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `INV-2 정합성 뷰는 잔고와 원장의 차이를 드러낸다`() {
        val accountId = createAccount(cash = 1_000_000)

        // 원장 없이 잔고만 넣었으므로 drift 가 발생해야 한다.
        val driftBefore = queryDrift(accountId)
        assertThat(driftBefore).isEqualTo(1_000_000)

        // 원장을 채우면 drift 가 0 이 된다.
        jdbc.sql(
            """
            INSERT INTO ledger (account_id, entry_type, amount, balance_after)
            VALUES (:accountId, 'DEPOSIT', 1000000, 1000000)
            """.trimIndent()
        ).param("accountId", accountId).update()

        assertThat(queryDrift(accountId)).isZero()
    }

    private fun queryDrift(accountId: Long): Long =
        jdbc.sql("SELECT drift FROM v_account_ledger_consistency WHERE account_id = :id")
            .param("id", accountId)
            .query(Long::class.java)
            .single()

    private fun insertOrder(
        accountId: Long,
        code: String,
        quantity: Long = 10,
        filledQuantity: Long = 0,
        status: String = "PENDING",
        idempotencyKey: String = "idem-${nextUserId()}",
    ) {
        jdbc.sql(
            """
            INSERT INTO orders
                (account_id, code, side, type, limit_price, quantity,
                 filled_quantity, status, idempotency_key)
            VALUES (:accountId, :code, 'BUY', 'LIMIT', 70000, :quantity,
                    :filledQuantity, :status, :key)
            """.trimIndent()
        )
            .param("accountId", accountId)
            .param("code", code)
            .param("quantity", quantity)
            .param("filledQuantity", filledQuantity)
            .param("status", status)
            .param("key", idempotencyKey)
            .update()
    }
}
