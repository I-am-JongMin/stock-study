package com.stockstudy.marketdata.kiwoom

import com.stockstudy.marketdata.kiwoom.raw.KiwoomTokenExpiry
import com.stockstudy.marketdata.kiwoom.raw.TokenRawRequest
import com.stockstudy.marketdata.kiwoom.raw.TokenRawResponse
import com.stockstudy.support.secret.Secret
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.time.Clock

/**
 * `POST /oauth2/token` — 접근토큰 발급 (client_credentials).
 *
 * 캐시도 락도 모른다. 그것은 [KiwoomTokenProvider] 의 일이다.
 */
class KiwoomTokenApi(
    private val rest: RestClient,
    private val properties: KiwoomProperties,
    private val clock: Clock,
) : AccessTokenIssuer {

    override fun issue(): IssuedToken {
        val issuedAt = clock.instant()

        val raw = rest.post()
            .uri("/oauth2/token")
            .contentType(MediaType.APPLICATION_JSON)
            // 자격증명 평문이 나가는 지점 (ADR-022). grep "reveal()" 로 전수 열거된다.
            .body(TokenRawRequest(properties.appKey.reveal(), properties.appSecret.reveal()))
            .retrieve()
            .body<TokenRawResponse>()
            ?: throw KiwoomApiException(
                TokenRawResponse.UNSET_RETURN_CODE, "응답 본문이 비어 있습니다",
                KiwoomErrorKind.OTHER, "토큰 발급",
            )

        if (raw.returnCode != 0) {
            throw KiwoomApiException(
                raw.returnCode, raw.returnMsg, KiwoomErrorKind.of(raw.returnCode), "토큰 발급",
            )
        }

        val plain = raw.token?.takeIf { it.isNotBlank() }
            ?: throw KiwoomApiException(
                raw.returnCode, "return_code 는 0 인데 토큰이 비어 있습니다",
                KiwoomErrorKind.OTHER, "토큰 발급",
            )

        val expiresAt = KiwoomTokenExpiry.parse(raw.expiresDt, issuedAt)
            ?: (issuedAt + KiwoomTokenExpiry.CONSERVATIVE_LIFETIME).also {
                // 토큰 자체는 멀쩡하다. 잃은 것은 만료 시각에 대한 이해뿐이므로 죽이지 않고
                // 짧게 잡는다. 이 경고가 뜨면 키움이 형식을 바꿨다는 뜻이다.
                log.warn(
                    "expires_dt 를 해석하지 못해 보수적 수명({})으로 대체합니다. " +
                        "키움이 형식을 바꿨는지 확인이 필요합니다.",
                    KiwoomTokenExpiry.CONSERVATIVE_LIFETIME,
                )
            }

        return IssuedToken(Secret(plain), issuedAt, expiresAt)
    }

    private companion object {
        val log = LoggerFactory.getLogger(KiwoomTokenApi::class.java)
    }
}
