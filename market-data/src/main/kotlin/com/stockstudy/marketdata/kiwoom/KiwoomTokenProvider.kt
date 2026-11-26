package com.stockstudy.marketdata.kiwoom

import com.stockstudy.marketdata.lock.LockOutcome
import com.stockstudy.marketdata.lock.RedisDistributedLock
import com.stockstudy.support.secret.Secret
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 접근토큰 캐시와 갱신 (§R3, ADR-024).
 *
 * **막으려는 사고** — 토큰 만료 순간에 요청 여러 개가 동시에 갱신을 시도하면, 발급 API
 * 자체의 레이트 리밋에 걸려 **전부 실패한다.** 토큰이 없어 장애가 나는 것이 아니라,
 * 토큰을 받으러 몰려가서 장애가 난다.
 *
 * **3단 구조**
 *
 * ```
 * ① 프로세스 안 캐시   — Redis 왕복조차 없다. 대부분의 호출이 여기서 끝난다
 * ② Redis 공유 캐시    — 인스턴스가 여러 대여도 발급은 한 번이면 된다
 * ③ 분산락 + 발급      — 락을 잡은 하나만 발급한다. 나머지는 ②를 다시 본다
 * ```
 *
 * **선제 갱신** — 남은 유효시간이 전체의 [TokenProperties.refreshMargin] 미만이면 아직
 * 유효해도 갱신한다. 만료 순간의 동시 요청이라는 상황 자체를 만들지 않는 것이 목적이다.
 * 중요한 것은 **선제 갱신이 경합에 져도 서비스가 멈추지 않는다**는 점이다 — 락을 못 잡았고
 * 손에 아직 쓸 수 있는 토큰이 있으면 기다리지 않고 그것을 쓴다. 최적화가 장애 원인이 되면
 * 최적화가 아니다.
 *
 * **락 안에서 한 번 더 확인하는 이유** — 내가 재시도를 기다리는 사이 다른 쪽이 갱신을
 * 끝내고 락을 풀었을 수 있다. 그 상태로 락을 잡으면 방금 발급된 토큰을 두고 또 발급한다.
 * 이중 확인(double-checked locking)이 없으면 하네스 V5b 가 "발급 정확히 1회"에서 깨진다.
 */
class KiwoomTokenProvider(
    private val redis: StringRedisTemplate,
    private val lock: RedisDistributedLock,
    private val issuer: AccessTokenIssuer,
    private val properties: KiwoomProperties,
    private val clock: Clock,
) : AccessTokens {

    @Volatile
    private var local: CachedToken? = null

    /** 유효한 접근토큰. 필요하면 발급까지 책임진다. */
    override fun accessToken(): Secret {
        val now = clock.instant()
        local?.takeIf { it.fresh(now, margin) }?.let { return it.secret }
        readShared()?.takeIf { it.fresh(now, margin) }?.let { adopt(it); return it.secret }
        return refreshWithLock()
    }

    /**
     * 캐시를 버린다. 서버가 "이 토큰은 무효다"(8005) 라고 답했을 때 호출한다 —
     * 만료 시각만 보고는 알 수 없는 상태이므로, 서버의 말을 신호로 삼는다.
     */
    override fun invalidate() {
        local = null
        redis.delete(token.cacheKey)
    }

    private fun refreshWithLock(): Secret {
        // 아직 쓸 수 있는(만료되지 않은) 토큰. 선제 갱신이 경합에 졌을 때의 퇴로다.
        val fallback = (local ?: readShared())?.takeIf { it.usable(clock.instant()) }

        // 대기 데드라인은 [clock] 이 아니라 단조 시계로 잰다. [clock] 은 "토큰이 언제
        // 만료되는가" 라는 도메인 시간을 위한 것이고, 여기서 필요한 것은 "얼마나 기다렸나"
        // 라는 경과 시간이다. NTP 보정으로 벽시계가 뒤로 점프하면 데드라인이 밀려
        // 영원히 기다리게 된다 — 테스트에서 시계를 멈춰보고 실제로 겪었다.
        val deadlineNanos = System.nanoTime() + token.waitTimeout.toNanos()

        while (true) {
            when (val outcome = lock.tryWithLock(token.lockKey, token.lockTtl) { issueOrAdopt() }) {
                is LockOutcome.Executed -> return outcome.value

                LockOutcome.NotAcquired -> {
                    readShared()?.takeIf { it.fresh(clock.instant(), margin) }
                        ?.let { adopt(it); return it.secret }

                    fallback?.takeIf { it.usable(clock.instant()) }?.let { return it.secret }

                    if (System.nanoTime() - deadlineNanos >= 0) {
                        throw KiwoomTokenUnavailableException(
                            "토큰 갱신을 ${token.waitTimeout} 동안 기다렸지만 얻지 못했습니다. " +
                                "락 보유자가 실패했거나 발급 API 가 응답하지 않습니다 (§R3).",
                        )
                    }
                    // 가상 스레드에서는 이 대기가 캐리어 스레드를 잡지 않는다 (ADR-003).
                    Thread.sleep(token.retryInterval.toMillis())
                }
            }
        }
    }

    /** 락 안에서 실행된다. 이중 확인 후 발급한다. */
    private fun issueOrAdopt(): Secret {
        readShared()?.takeIf { it.fresh(clock.instant(), margin) }
            ?.let { adopt(it); return it.secret }

        val issued = issuer.issue()
        val fresh = CachedToken(issued.token, issued.issuedAt, issued.expiresAt)
        store(fresh)
        adopt(fresh)
        log.info(
            "키움 접근토큰 발급 완료 — 만료 {} (유효기간 {})",
            fresh.expiresAt, Duration.between(fresh.issuedAt, fresh.expiresAt),
        )
        return fresh.secret
    }

    private fun adopt(cached: CachedToken) {
        local = cached
    }

    private fun readShared(): CachedToken? =
        redis.opsForValue().get(token.cacheKey)?.let(CachedToken::decode)

    private fun store(cached: CachedToken) {
        val ttl = Duration.between(clock.instant(), cached.expiresAt)
        // 캐시가 토큰보다 오래 살면 안 된다. 죽은 토큰을 공유하는 것이 캐시가 비는 것보다 나쁘다.
        if (ttl.isPositive) redis.opsForValue().set(token.cacheKey, cached.encode(), ttl)
    }

    private val token: TokenProperties get() = properties.token
    private val margin: Double get() = properties.token.refreshMargin

    private companion object {
        val log = LoggerFactory.getLogger(KiwoomTokenProvider::class.java)
    }
}

/**
 * 캐시에 담기는 토큰. 저장 형식은 `발급시각:만료시각:평문` 이다.
 *
 * JSON 직렬화를 쓰지 않는 이유 — [Secret] 은 Jackson 으로 평문이 나가지 않도록
 * 일부러 만든 타입이다 (ADR-022). 그 보장을 캐시 저장이라는 이유로 우회하는 코드를 두면,
 * 언제 어디서 같은 우회가 재사용될지 알 수 없다. 평문이 필요한 지점은 `reveal()` 한 번으로
 * 눈에 띄게 둔다.
 */
internal class CachedToken(
    val secret: Secret,
    val issuedAt: Instant,
    val expiresAt: Instant,
) {
    /** 남은 유효시간이 전체 수명의 [margin] 을 넘는가 — 넘지 못하면 선제 갱신 대상이다. */
    fun fresh(now: Instant, margin: Double): Boolean {
        val total = Duration.between(issuedAt, expiresAt)
        val remaining = Duration.between(now, expiresAt)
        if (remaining.isNegative || remaining.isZero) return false
        return remaining.toMillis() > total.toMillis() * margin
    }

    /** 선제 갱신 기준에는 못 미쳐도 아직 **쓸 수 있는가**. 시계 오차와 비행 중 요청을 감안한다. */
    fun usable(now: Instant): Boolean = now.isBefore(expiresAt - SAFETY_MARGIN)

    fun encode(): String = "${issuedAt.epochSecond}:${expiresAt.epochSecond}:${secret.reveal()}"

    companion object {
        private val SAFETY_MARGIN: Duration = Duration.ofSeconds(5)

        fun decode(encoded: String): CachedToken? {
            val parts = encoded.split(':', limit = 3)
            if (parts.size != 3) return null
            val issuedAt = parts[0].toLongOrNull() ?: return null
            val expiresAt = parts[1].toLongOrNull() ?: return null
            if (parts[2].isBlank()) return null
            return CachedToken(
                Secret(parts[2]),
                Instant.ofEpochSecond(issuedAt),
                Instant.ofEpochSecond(expiresAt),
            )
        }
    }
}
