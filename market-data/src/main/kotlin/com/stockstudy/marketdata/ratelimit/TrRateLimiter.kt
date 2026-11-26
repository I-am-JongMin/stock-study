package com.stockstudy.marketdata.ratelimit

import java.time.Duration

/**
 * 키움 TR 호출 간격 제한 (ADR-025).
 *
 * 모의투자는 **계좌(토큰)별 TR 1 개당 초당 1 회** 수준이다. 종목마스터 동기화처럼 같은
 * TR 을 반복 호출하는 배치는 아무 대책 없이 돌면 즉시 막힌다. 이건 버그가 아니라
 * 설계 과제다 — 호출 측이 알아서 조심하는 것이 아니라, 호출 경로가 구조적으로 느려야 한다.
 */
interface TrRateLimiter {

    /**
     * [apiId] 의 차례가 올 때까지 기다렸다가 [block] 을 실행한다.
     *
     * @throws RateLimitExceededException 기다려야 하는 시간이 한도를 넘을 때. 무한정
     *   기다리면 스레드가 쌓이고, 그 적체가 레이트 리밋보다 먼저 서비스를 죽인다.
     */
    fun <T> withPermit(apiId: String, block: () -> T): T

    /**
     * 제한하지 않는다. 계약 검증(V4)처럼 **응답 해석만** 증명하는 테스트용이다.
     *
     * 기본값으로 쓰지 않는다 — 기본이 '무제한'이면 배선을 빠뜨린 코드가 조용히 통과한다.
     */
    object Disabled : TrRateLimiter {
        override fun <T> withPermit(apiId: String, block: () -> T): T = block()
    }
}

/** 차례를 기다리는 데 걸리는 시간이 [maxWait] 를 넘었다. */
class RateLimitExceededException(apiId: String, maxWait: Duration) :
    RuntimeException("$apiId 호출 차례를 ${maxWait} 안에 받지 못했습니다. 호출이 적체되고 있습니다 (ADR-025).")
