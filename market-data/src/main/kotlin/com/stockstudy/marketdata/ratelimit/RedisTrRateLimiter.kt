package com.stockstudy.marketdata.ratelimit

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Duration

/**
 * Redis 에 **다음 호출 허용 시각을 예약**하는 방식의 간격 제한 (ADR-025).
 *
 * ```
 * 요청 A (t=0)    → 예약 없음. 즉시 통과하고 next = 1000 으로 예약
 * 요청 B (t=10)   → next(1000) 까지 990ms 기다리라고 답하고 next = 2000 으로 예약
 * 요청 C (t=20)   → next(2000) 까지 1980ms 기다리라고 답하고 next = 3000 으로 예약
 * ```
 *
 * **왜 토큰 버킷이 아니라 간격 예약인가** — 키움의 제한은 "초당 N 개"가 아니라 사실상
 * "연속 호출 사이의 간격"이다. 버킷은 버스트를 허용하는 것이 장점인데, 여기서는 버스트가
 * 정확히 막으려는 것이다. 또 예약 방식은 **기다려야 할 시간을 호출부에 알려준다** —
 * 거부하고 재시도를 떠넘기는 대신 정확히 그만큼만 자면 된다.
 *
 * **시각을 Redis 서버에서 읽는 이유** — 인스턴스가 여러 대면 각자의 벽시계가 조금씩
 * 다르다. 클라이언트 시각으로 예약하면 시계가 앞선 인스턴스가 남의 자리를 밀고 들어간다.
 * Lua 안에서 `TIME` 을 부르면 비교 기준이 하나가 된다.
 *
 * 한계 — 이것은 **협조적** 제한이다. 이 경로를 지나지 않는 호출은 막지 못한다. 그래서
 * 키움 호출은 전부 클라이언트를 통하게 두고, 클라이언트가 이 리미터를 지나게 배선한다.
 */
class RedisTrRateLimiter(
    private val redis: StringRedisTemplate,
    private val keyPrefix: String,
    private val interval: Duration,
    private val maxWait: Duration,
) : TrRateLimiter {

    override fun <T> withPermit(apiId: String, block: () -> T): T {
        val waitMillis = reserve(apiId)
        if (waitMillis < 0) throw RateLimitExceededException(apiId, maxWait)

        if (waitMillis > 0) {
            log.debug("{} — 호출 간격 유지를 위해 {}ms 대기", apiId, waitMillis)
            // 가상 스레드에서 이 대기는 캐리어 스레드를 잡지 않는다 (ADR-003).
            Thread.sleep(waitMillis)
        }
        return block()
    }

    /** @return 기다려야 할 밀리초. 음수면 [maxWait] 를 넘어 예약하지 않았다는 뜻이다. */
    private fun reserve(apiId: String): Long =
        redis.execute(
            RESERVE_SCRIPT,
            listOf("$keyPrefix$apiId"),
            interval.toMillis().toString(),
            maxWait.toMillis().toString(),
            // 예약이 살아 있어야 하는 최소 시간. 넉넉히 잡아도 비용은 키 하나다.
            (maxWait.toMillis() + interval.toMillis() * 2).toString(),
        )

    private companion object {
        val log = LoggerFactory.getLogger(RedisTrRateLimiter::class.java)

        /**
         * 예약을 읽고 → 대기시간을 계산하고 → 다음 예약을 쓰는 세 단계가 원자적이어야 한다.
         * 나뉘면 두 호출이 같은 슬롯을 예약한다.
         *
         * 한도를 넘는 경우 **예약을 쓰지 않는다** — 거부해놓고 자리를 잡아버리면
         * 그 슬롯이 아무도 쓰지 않은 채 흘러간다.
         */
        val RESERVE_SCRIPT = DefaultRedisScript(
            """
            local interval = tonumber(ARGV[1])
            local maxWait  = tonumber(ARGV[2])
            local ttl      = tonumber(ARGV[3])

            local time = redis.call('TIME')
            local now = (tonumber(time[1]) * 1000) + math.floor(tonumber(time[2]) / 1000)

            local nextAllowed = tonumber(redis.call('GET', KEYS[1]) or '0')
            if nextAllowed < now then nextAllowed = now end

            local wait = nextAllowed - now
            if wait > maxWait then return -1 end

            redis.call('SET', KEYS[1], nextAllowed + interval, 'PX', ttl)
            return wait
            """.trimIndent(),
            Long::class.java,
        )
    }
}
