package com.stockstudy.marketdata.kiwoom

import com.stockstudy.support.secret.Secret
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 키움 REST API 설정 (ADR-021).
 *
 * 비밀값은 [String] 이 아니라 [Secret] 으로 들고 있는다 (ADR-022). 이 클래스가
 * `data class` 가 아닌 것도 같은 이유다 — 자동 생성된 `toString()` 이 설정을 통째로
 * 로그에 흘리는 가장 흔한 경로다.
 */
@ConfigurationProperties(prefix = "kiwoom")
class KiwoomProperties(
    /** 모의투자가 기본값이다. 실전 전환은 환경변수 교체로만 한다 (ADR-020/021, 하네스 G4). */
    val baseUrl: String,
    appKey: String,
    appSecret: String,
    accountNo: String = "",
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val readTimeout: Duration = Duration.ofSeconds(5),
    val token: TokenProperties = TokenProperties(),
) {
    val appKey: Secret = Secret(appKey)
    val appSecret: Secret = Secret(appSecret)
    val accountNo: Secret = Secret(accountNo)

    init {
        // ADR-024 — 임계구역이 락 TTL 보다 길어지면 둘 이상이 동시에 토큰을 발급한다.
        // HTTP 가 먼저 타임아웃되도록 설정 단계에서 강제한다. 운영 중에 어기는 것보다
        // 기동 자체를 실패시키는 쪽이 싸다.
        val worstCase = connectTimeout + readTimeout
        require(worstCase < token.lockTtl) {
            "HTTP 최악 지연(${worstCase}) 이 토큰 락 TTL(${token.lockTtl}) 보다 짧아야 합니다. " +
                "그렇지 않으면 락이 먼저 풀려 동시 발급이 일어납니다 (ADR-024)."
        }
    }

    /** 비밀이 하나라도 비어 있으면 키움 호출을 시도할 수 없다. 기동 시 한 번 확인한다. */
    fun credentialsConfigured(): Boolean = !appKey.blank() && !appSecret.blank()

    override fun toString(): String = "KiwoomProperties(baseUrl=$baseUrl, token=$token)"
}

/** 접근토큰 캐시·갱신 정책 (§R3). */
class TokenProperties(
    val cacheKey: String = "kiwoom:token",
    val lockKey: String = "kiwoom:token:lock",
    /** 락 TTL. 발급 HTTP 최악 지연보다 길어야 한다 — [KiwoomProperties] 의 init 이 검증한다. */
    val lockTtl: Duration = Duration.ofSeconds(10),
    /** 캐시가 비어 있고 남이 갱신 중일 때 기다릴 최대 시간. */
    val waitTimeout: Duration = Duration.ofSeconds(10),
    val retryInterval: Duration = Duration.ofMillis(50),
    /**
     * 남은 유효시간이 전체의 이 비율 미만이면 **선제적으로** 갱신한다 (§R3).
     * 만료 순간에 동시 요청이 몰리는 상황 자체를 피한다.
     */
    val refreshMargin: Double = 0.1,
) {
    init {
        require(refreshMargin in 0.0..0.5) { "refreshMargin 은 0.0~0.5 사이여야 합니다: $refreshMargin" }
    }

    override fun toString(): String =
        "TokenProperties(lockTtl=$lockTtl, waitTimeout=$waitTimeout, refreshMargin=$refreshMargin)"
}
