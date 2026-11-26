package com.stockstudy.marketdata.lock

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Duration
import java.util.UUID

/**
 * `SET NX PX` 기반 Redis 분산락 (ADR-024).
 *
 * **이 락이 보장하는 것과 보장하지 않는 것을 먼저 읽는다.**
 *
 * 보장하지 않는다 — Redis 단일 인스턴스 락은 **정합성 경계가 아니다.** 임계구역이 [Duration]
 * TTL 보다 길어지거나(GC 정지·네트워크 지연), 마스터가 복제 전에 죽고 레플리카가 승격되면
 * 둘 이상이 동시에 임계구역에 들어간다. 돈이 걸린 정합성은 DB 제약과 조건부 UPDATE 로
 * 지킨다 (ADR-009).
 *
 * 보장하려는 것 — **중복 작업 억제.** 만료된 토큰을 여러 요청이 동시에 갱신하려 할 때
 * 발급 호출이 한 번만 나가게 하는 용도다 (§R3). 두 번 나가도 결과는 호출 한 번의 낭비이지
 * 데이터 손상이 아니므로, 이 강도의 락이 정확히 맞는 문제다.
 *
 * **해제가 Lua 인 이유** — `DEL key` 로 해제하면 다음이 일어난다.
 *
 * ```
 * A: 락 획득 (TTL 30s)
 * A: 임계구역이 31초 걸림 → 락이 만료됨
 * B: 락 획득 (A 와 무관한 새 락)
 * A: 뒤늦게 DEL → B 의 락을 A 가 풀어버린다
 * ```
 *
 * GET-비교-DEL 이 원자적이어야 막을 수 있고, Redis 에서 원자성의 단위는 Lua 스크립트다.
 */
class RedisDistributedLock(
    private val redis: StringRedisTemplate,
) {

    /**
     * [key] 락을 잡고 [block] 을 실행한다. 획득하지 못하면 **기다리지 않고** 즉시
     * [LockOutcome.NotAcquired] 를 돌려준다 — 기다릴지 말지는 호출부가 정한다.
     *
     * [block] 이 예외를 던져도 락은 해제된다. 해제하지 않으면 TTL 이 지날 때까지 아무도
     * 재시도하지 못한다.
     */
    fun <T> tryWithLock(key: String, ttl: Duration, block: () -> T): LockOutcome<T> {
        val owner = tryAcquire(key, ttl) ?: return LockOutcome.NotAcquired

        val value: T
        try {
            value = block()
        } finally {
            releaseAndWarn(key, owner, ttl)
        }
        return LockOutcome.Executed(value)
    }

    /** 성공하면 소유자 토큰을, 이미 누가 들고 있으면 null 을 돌려준다. */
    fun tryAcquire(key: String, ttl: Duration): String? {
        val owner = UUID.randomUUID().toString()
        val acquired = redis.opsForValue().setIfAbsent(key, owner, ttl)
        return if (acquired == true) owner else null
    }

    /**
     * 내가 소유자일 때만 해제한다.
     *
     * @return `false` 면 **락이 이미 내 것이 아니었다** — 임계구역이 TTL 을 넘겼다는 뜻이다.
     *   그 사이 다른 인스턴스가 같은 구역에 들어가 있었을 수 있다.
     */
    fun release(key: String, owner: String): Boolean =
        redis.execute(RELEASE_SCRIPT, listOf(key), owner) == 1L

    private fun releaseAndWarn(key: String, owner: String, ttl: Duration) {
        if (!release(key, owner)) {
            // 조용히 넘기면 TTL 이 부족하다는 사실을 영영 모른다. TTL 을 늘려야 한다는
            // 유일한 객관적 증거이므로 반드시 남긴다 (ADR-024).
            log.warn(
                "분산락 해제 실패 — 임계구역이 TTL({}ms)을 넘겼습니다. key={} " +
                    "다른 인스턴스가 같은 구역에 동시에 들어갔을 수 있습니다.",
                ttl.toMillis(), key,
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RedisDistributedLock::class.java)

        private val RELEASE_SCRIPT = DefaultRedisScript(
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            else
                return 0
            end
            """.trimIndent(),
            Long::class.java,
        )
    }
}

/** 락 획득 여부와 실행 결과를 함께 나르는 타입. `null` 로 둘을 겸하면 구분이 사라진다. */
sealed interface LockOutcome<out T> {

    /** 락을 잡고 블록을 실행했다. */
    data class Executed<T>(val value: T) : LockOutcome<T>

    /** 다른 누군가가 들고 있어 실행하지 않았다. */
    data object NotAcquired : LockOutcome<Nothing>
}
