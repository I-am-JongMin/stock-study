package com.stockstudy.marketdata.ratelimit

import com.stockstudy.marketdata.support.RedisTestSupport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 하네스 V8 — TR 호출 간격 제한 (ADR-025, docs/harness.md).
 *
 * 간격을 실제로 벌리는지는 **경과 시간을 재서만** 알 수 있고, 여러 인스턴스가 같은
 * 예약을 두고 다투는 것은 진짜 Redis 에서만 재현된다.
 */
@DisplayName("V8 — TR 레이트 리미터")
class RedisTrRateLimiterTest {

    private val redis = RedisTestSupport.template

    @BeforeEach
    fun clean() = RedisTestSupport.flush()

    @Test
    @DisplayName("연속 호출은 설정한 간격만큼 벌어진다")
    fun callsAreSpaced() {
        val limiter = limiter(interval = Duration.ofMillis(200))

        val elapsed = measure {
            repeat(3) { limiter.withPermit(API_ID) { } }
        }

        // 1회차는 즉시 통과하므로 간격은 2번 들어간다.
        elapsed shouldBeGreaterThanOrEqualTo Duration.ofMillis(400)
        elapsed shouldBeLessThan Duration.ofMillis(1_500)
    }

    @Test
    @DisplayName("가상 스레드 10개가 동시에 몰려도 각자 다른 슬롯을 받는다")
    fun concurrentCallersGetDistinctSlots() {
        val limiter = limiter(interval = Duration.ofMillis(100))
        val concurrency = 10
        val ready = CountDownLatch(concurrency)
        val gate = CountDownLatch(1)
        val completions = ConcurrentLinkedQueue<Long>()

        val elapsed = measure {
            Executors.newVirtualThreadPerTaskExecutor().use { pool ->
                repeat(concurrency) {
                    pool.submit {
                        ready.countDown()
                        gate.await()
                        limiter.withPermit(API_ID) { completions += System.nanoTime() }
                    }
                }
                ready.await()
                gate.countDown()
            }
        }

        completions shouldHaveSize concurrency
        // 10개가 100ms 간격이면 최소 900ms 다. 동시에 통과했다면 이 조건이 깨진다.
        elapsed shouldBeGreaterThanOrEqualTo Duration.ofMillis(900)

        // 인접한 두 통과 사이가 실제로 벌어져 있는지도 본다.
        val gaps = completions.sorted().zipWithNext { a, b -> (b - a) / 1_000_000 }
        gaps.forEach { it shouldBeGreaterThanOrEqualTo MIN_GAP_MILLIS }
    }

    @Test
    @DisplayName("기다려야 할 시간이 한도를 넘으면 거부한다 — 무한 대기는 스레드 적체로 먼저 죽는다")
    fun rejectsWhenWaitExceedsLimit() {
        val limiter = limiter(interval = Duration.ofSeconds(1), maxWait = Duration.ofMillis(100))

        limiter.withPermit(API_ID) { } // 즉시 통과, 1초 뒤로 예약

        shouldThrow<RateLimitExceededException> { limiter.withPermit(API_ID) { } }
    }

    @Test
    @DisplayName("거부된 호출은 슬롯을 잡지 않는다 — 아무도 쓰지 않는 자리가 생기면 안 된다")
    fun rejectedCallDoesNotConsumeASlot() {
        val limiter = limiter(interval = Duration.ofMillis(500), maxWait = Duration.ofMillis(50))
        val executions = AtomicInteger()

        limiter.withPermit(API_ID) { executions.incrementAndGet() }
        shouldThrow<RateLimitExceededException> { limiter.withPermit(API_ID) { executions.incrementAndGet() } }

        Thread.sleep(600) // 첫 예약이 지나간다

        // 거부된 호출이 예약을 밀어놨다면 여기서 또 거부된다.
        limiter.withPermit(API_ID) { executions.incrementAndGet() }
        executions.get() shouldBe 2
    }

    @Test
    @DisplayName("TR 이 다르면 서로를 막지 않는다 — 한도는 TR 단위다")
    fun differentApiIdsAreIndependent() {
        val limiter = limiter(interval = Duration.ofMillis(800))

        val elapsed = measure {
            limiter.withPermit("ka10001") { }
            limiter.withPermit("ka10081") { }
            limiter.withPermit("ka10099") { }
        }

        elapsed shouldBeLessThan Duration.ofMillis(400)
    }

    private fun limiter(
        interval: Duration,
        maxWait: Duration = Duration.ofSeconds(30),
    ) = RedisTrRateLimiter(redis, KEY_PREFIX, interval, maxWait)

    private fun measure(block: () -> Unit): Duration {
        val started = System.nanoTime()
        block()
        return Duration.ofNanos(System.nanoTime() - started)
    }

    private companion object {
        const val KEY_PREFIX = "test:kiwoom:rl:"
        const val API_ID = "ka10001"

        /** 간격 100ms 에 대한 하한. 스케줄링 오차를 감안해 조금 낮춘다. */
        const val MIN_GAP_MILLIS = 80L
    }
}
