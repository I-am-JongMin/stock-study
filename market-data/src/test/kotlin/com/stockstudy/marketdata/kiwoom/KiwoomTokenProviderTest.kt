package com.stockstudy.marketdata.kiwoom

import com.stockstudy.marketdata.lock.RedisDistributedLock
import com.stockstudy.marketdata.support.RedisTestSupport
import com.stockstudy.support.secret.Secret
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 하네스 V5b — 토큰 갱신 레이스 (§R3, ADR-024).
 *
 * **증명하려는 것** — 만료 순간에 요청이 몰려도 발급 API 를 **정확히 한 번만** 때린다.
 * 이것이 깨지면 토큰이 없어서가 아니라 토큰을 받으러 몰려가서 장애가 난다.
 *
 * Redis 는 진짜를 쓰고 HTTP 만 가짜로 둔다. 락과 캐시가 검증 대상이므로 그쪽을 가짜로
 * 바꾸면 증명할 것이 남지 않는다.
 */
@DisplayName("V5b — 접근토큰 캐시·갱신")
class KiwoomTokenProviderTest {

    private val redis = RedisTestSupport.template
    private val lock = RedisDistributedLock(redis)
    private val clock = MutableClock(Instant.parse("2026-11-26T12:00:00Z"))

    @BeforeEach
    fun clean() = RedisTestSupport.flush()

    @Test
    @DisplayName("가상 스레드 50개가 동시에 토큰을 요구해도 발급은 정확히 1회다")
    fun concurrentRequestsIssueExactlyOnce() {
        // 발급이 순식간에 끝나면 경합이 재현되지 않는다. 실제 HTTP 왕복만큼 느리게 둔다.
        val issuer = CountingIssuer(clock, delay = Duration.ofMillis(300))
        val provider = provider(issuer)
        val concurrency = 50
        val ready = CountDownLatch(concurrency)
        val gate = CountDownLatch(1)
        val seen = ConcurrentLinkedQueue<String>()

        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            repeat(concurrency) {
                pool.submit {
                    ready.countDown()
                    gate.await()
                    seen += provider.accessToken().reveal()
                }
            }
            ready.await()
            gate.countDown()
        }

        issuer.calls.get() shouldBe 1
        seen shouldHaveSize concurrency
        seen.toSet() shouldBe setOf("token-1") // 전부 같은 토큰을 봤다
    }

    @Test
    @DisplayName("두 번째 호출은 프로세스 안 캐시에서 끝난다")
    fun secondCallHitsLocalCache() {
        val issuer = CountingIssuer(clock)
        val provider = provider(issuer)

        provider.accessToken().reveal() shouldBe "token-1"
        provider.accessToken().reveal() shouldBe "token-1"

        issuer.calls.get() shouldBe 1
    }

    @Test
    @DisplayName("다른 인스턴스가 이미 발급했으면 공유 캐시를 쓴다 — 인스턴스를 늘려도 발급은 1회")
    fun otherInstanceSharesTokenThroughRedis() {
        val issuer = CountingIssuer(clock)
        val instanceA = provider(issuer)
        val instanceB = provider(issuer) // 같은 Redis, 다른 프로세스 캐시

        instanceA.accessToken().reveal() shouldBe "token-1"
        instanceB.accessToken().reveal() shouldBe "token-1"

        issuer.calls.get() shouldBe 1
    }

    @Test
    @DisplayName("남은 유효시간이 10% 아래로 떨어지면 만료 전에 선제적으로 갱신한다")
    fun refreshesPreemptively() {
        val issuer = CountingIssuer(clock, lifetime = Duration.ofHours(24))
        val provider = provider(issuer)

        provider.accessToken().reveal() shouldBe "token-1"

        // 21시간 경과 — 아직 3시간 남았지만 전체의 12.5% 라 아직 갱신하지 않는다
        clock += Duration.ofHours(21)
        provider.accessToken().reveal() shouldBe "token-1"
        issuer.calls.get() shouldBe 1

        // 22시간 경과 — 남은 2시간은 전체의 8.3% 로 기준(10%) 아래다
        clock += Duration.ofHours(1)
        provider.accessToken().reveal() shouldBe "token-2"
        issuer.calls.get() shouldBe 2
    }

    @Test
    @DisplayName("선제 갱신이 락 경합에 져도 멈추지 않는다 — 아직 유효한 토큰으로 답한다")
    fun preemptiveRefreshNeverBlocksService() {
        val issuer = CountingIssuer(clock, lifetime = Duration.ofHours(24))
        val provider = provider(issuer)
        provider.accessToken().reveal() shouldBe "token-1"

        clock += Duration.ofHours(23) // 선제 갱신 대상이 되었지만 토큰은 아직 유효하다
        // 다른 인스턴스가 갱신 중인 상황을 만든다.
        lock.tryAcquire(LOCK_KEY, Duration.ofSeconds(30)) shouldNotBe null

        val started = clock.instant()
        provider.accessToken().reveal() shouldBe "token-1"

        // 기다리지 않았다는 것까지 확인한다 — waitTimeout 을 소진했다면 그 자체가 장애다.
        issuer.calls.get() shouldBe 1
        clock.instant() shouldBe started
    }

    @Test
    @DisplayName("쓸 토큰이 아예 없는데 남이 락을 쥐고 있으면 대기 후 정직하게 실패한다")
    fun failsWhenNoTokenAndLockHeld() {
        val issuer = CountingIssuer(clock)
        val provider = provider(issuer, waitTimeout = Duration.ofMillis(300))
        lock.tryAcquire(LOCK_KEY, Duration.ofSeconds(30))

        val thrown = shouldThrow<KiwoomTokenUnavailableException> { provider.accessToken() }

        thrown.message!!.contains("§R3") shouldBe true
        issuer.calls.get() shouldBe 0
    }

    @Test
    @DisplayName("서버가 토큰을 거부해 무효화하면 공유 캐시까지 버리고 다시 발급한다")
    fun invalidateClearsSharedCache() {
        val issuer = CountingIssuer(clock)
        val provider = provider(issuer)
        provider.accessToken().reveal() shouldBe "token-1"

        provider.invalidate()

        provider.accessToken().reveal() shouldBe "token-2"
        issuer.calls.get() shouldBe 2
    }

    // ── 조립 ──────────────────────────────────────────────────────────────

    private fun provider(
        issuer: AccessTokenIssuer,
        waitTimeout: Duration = Duration.ofSeconds(10),
    ): KiwoomTokenProvider {
        val properties = KiwoomProperties(
            baseUrl = "http://localhost:1", // HTTP 는 쓰지 않는다
            appKey = "k",
            appSecret = "s",
            token = TokenProperties(
                cacheKey = CACHE_KEY,
                lockKey = LOCK_KEY,
                waitTimeout = waitTimeout,
                retryInterval = Duration.ofMillis(20),
            ),
        )
        return KiwoomTokenProvider(redis, lock, issuer, properties, clock)
    }

    /** 호출 횟수를 세는 가짜 발급기. 토큰은 `token-1`, `token-2` … 로 구분된다. */
    private class CountingIssuer(
        private val clock: Clock,
        private val lifetime: Duration = Duration.ofHours(24),
        private val delay: Duration = Duration.ZERO,
    ) : AccessTokenIssuer {
        val calls = AtomicInteger()

        override fun issue(): IssuedToken {
            val sequence = calls.incrementAndGet()
            if (!delay.isZero) Thread.sleep(delay.toMillis())
            val issuedAt = clock.instant()
            return IssuedToken(Secret("token-$sequence"), issuedAt, issuedAt + lifetime)
        }
    }

    /** 시간을 손으로 돌리는 시계. 만료·선제갱신은 기다려서 검증할 수 없다. */
    private class MutableClock(@Volatile private var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
        operator fun plusAssign(amount: Duration) {
            now += amount
        }
    }

    private companion object {
        const val CACHE_KEY = "test:kiwoom:token"
        const val LOCK_KEY = "test:kiwoom:token:lock"
    }
}
