package com.stockstudy.marketdata.kiwoom

import com.stockstudy.support.secret.Secret
import java.time.Instant

/**
 * 발급받은 접근토큰.
 *
 * `data class` 이지만 [token] 이 [Secret] 이라 `toString()` 이 평문을 내보내지 않는다 —
 * 이것이 ADR-022 의 타입 방어가 실제로 값을 하는 지점이다.
 */
data class IssuedToken(
    val token: Secret,
    val issuedAt: Instant,
    val expiresAt: Instant,
)

/**
 * 토큰 발급 경로. [KiwoomTokenProvider] 가 이 좁은 인터페이스에만 의존한다.
 *
 * **왜 인터페이스로 끊는가** — 시세 API 는 토큰이 필요하고 토큰은 HTTP 호출로 받는다.
 * 한 클래스가 둘 다 하면 스스로를 참조하는 순환이 생긴다. 발급은 캐시·락을 모르고,
 * 캐시·락은 HTTP 를 모르게 둔다. 덕분에 동시성 검증(V5b)이 HTTP 를 가짜로 바꿔도 된다.
 */
fun interface AccessTokenIssuer {
    fun issue(): IssuedToken
}

/**
 * 토큰을 **쓰는** 쪽이 보는 역할. 시세 API 는 캐시 구조도 Redis 도 모른다.
 *
 * 구현은 [KiwoomTokenProvider] 하나지만 역할로 끊어 두면 계약 검증(V4)이 Redis 없이
 * 돌아간다 — 응답 해석을 증명하는 테스트가 인프라를 띄울 이유는 없다.
 */
interface AccessTokens {
    fun accessToken(): Secret

    /** 서버가 토큰을 거부했을 때 캐시를 버린다. */
    fun invalidate()
}
