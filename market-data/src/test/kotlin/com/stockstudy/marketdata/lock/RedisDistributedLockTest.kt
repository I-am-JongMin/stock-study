package com.stockstudy.marketdata.lock

import com.stockstudy.marketdata.support.RedisTestSupport
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 하네스 V5 — 분산락이 실제로 상호 배제를 만드는지 증명한다 (ADR-024, docs/harness.md).
 *
 * mock 으로는 아무것도 증명되지 않는 영역이다. `SET NX` 의 원자성도, Lua 해제의
 * 원자성도 진짜 Redis 에서만 나타난다.
 *
 * **동시성을 가상 스레드로 만드는 이유** — 운영에서 토큰을 동시에 요구하는 주체는
 * 가상 스레드 위의 HTTP 요청 처리 스레드들이다 (ADR-003). 재현 조건을 현실과 같게 둔다.
 * 코루틴 `Dispatchers.IO` 로 하면 블로킹 호출이 64개 스레드 풀을 메워, 경합을 재현하려다
 * 테스트 자신이 교착에 빠진다 — 실제로 한 번 겪고 바꿨다.
 */
@DisplayName("V5 — Redis 분산락")
class RedisDistributedLockTest {

    private val redis = RedisTestSupport.template
    private val lock = RedisDistributedLock(redis)

    @BeforeEach
    fun clean() = RedisTestSupport.flush()

    @Test
    @DisplayName("락이 잡혀 있는 동안 다른 시도는 실행되지 않는다")
    fun mutualExclusion() {
        val owner = lock.tryAcquire(KEY, TTL)
        owner shouldNotBe null

        lock.tryWithLock(KEY, TTL) { "실행됨" } shouldBe LockOutcome.NotAcquired

        lock.release(KEY, owner!!) shouldBe true
        lock.tryWithLock(KEY, TTL) { "실행됨" } shouldBe LockOutcome.Executed("실행됨")
    }

    @Test
    @DisplayName("가상 스레드 100개가 동시에 요구해도 임계구역에는 하나만 들어간다")
    fun onlyOneEntersCriticalSection() {
        val concurrency = 100
        val ready = CountDownLatch(concurrency)
        val gate = CountDownLatch(1)
        val inside = AtomicInteger(0)
        val maxInside = AtomicInteger(0)
        val executed = AtomicInteger(0)

        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            repeat(concurrency) {
                pool.submit {
                    // 출발선을 맞춘다. 순차 실행이 되면 락이 아니라 타이밍을 검증하게 된다.
                    ready.countDown()
                    gate.await()

                    val outcome = lock.tryWithLock(KEY, TTL) {
                        maxInside.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                        Thread.sleep(500)
                        inside.decrementAndGet()
                    }
                    if (outcome is LockOutcome.Executed) executed.incrementAndGet()
                }
            }
            ready.await()   // 100개가 전부 출발선에 선 뒤에
            gate.countDown() // 동시에 출발시킨다
        }

        maxInside.get() shouldBe 1
        executed.get() shouldBe 1
    }

    @Test
    @DisplayName("소유자가 아니면 해제하지 못한다 — 남의 락을 푸는 사고를 막는다")
    fun releaseRequiresOwnership() {
        val owner = lock.tryAcquire(KEY, TTL)!!

        lock.release(KEY, "다른-소유자-토큰") shouldBe false
        // 락은 그대로 남아 있어야 한다.
        lock.tryAcquire(KEY, TTL) shouldBe null

        lock.release(KEY, owner) shouldBe true
    }

    @Test
    @DisplayName("임계구역이 TTL 을 넘기면 해제가 실패하고, 그 사이 다른 쪽이 들어간다")
    fun criticalSectionLongerThanTtl() {
        val shortTtl = Duration.ofMillis(300)
        val owner = lock.tryAcquire(KEY, shortTtl)!!

        Thread.sleep(500) // TTL 만료 — 락은 사라졌다

        val intruder = lock.tryAcquire(KEY, TTL)
        intruder shouldNotBe null

        // 뒤늦은 해제가 침입자의 락을 풀어버리면 안 된다. 이것이 Lua 해제의 존재 이유다.
        lock.release(KEY, owner) shouldBe false
        lock.tryAcquire(KEY, TTL) shouldBe null

        lock.release(KEY, intruder!!) shouldBe true
    }

    @Test
    @DisplayName("블록이 예외를 던져도 락은 해제된다")
    fun releasedOnException() {
        assertThrows<IllegalStateException> {
            lock.tryWithLock(KEY, TTL) { error("임계구역에서 터짐") }
        }

        // 해제되지 않았다면 TTL 이 지날 때까지 아무도 재시도하지 못한다.
        lock.tryAcquire(KEY, TTL) shouldNotBe null
    }

    private companion object {
        const val KEY = "test:lock:token"
        val TTL: Duration = Duration.ofSeconds(30)
    }
}
