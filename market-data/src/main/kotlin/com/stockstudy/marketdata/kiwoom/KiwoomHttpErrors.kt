package com.stockstudy.marketdata.kiwoom

import com.fasterxml.jackson.databind.ObjectMapper
import com.stockstudy.marketdata.kiwoom.raw.ErrorRawResponse
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.ClientHttpResponse

/**
 * 비-2xx 응답을 [KiwoomApiException] 으로 번역한다.
 *
 * **왜 필요한가** — 키움은 대부분의 실패를 HTTP 200 + `return_code` 로 보내지만,
 * 유량 초과만은 429 로 온다 (라이브 스모크 V7 이 찾아냈다). 번역하지 않으면 호출부가
 * 어떤 날은 [KiwoomApiException] 을, 어떤 날은 `HttpClientErrorException` 을 받는다.
 * 같은 성질의 실패가 두 타입으로 나뉘면 처리 분기가 반드시 한쪽을 빠뜨린다.
 *
 * 본문을 읽지 못해도 상태코드만으로 최선의 분류를 한다 — 번역이 실패해서 원인이
 * 사라지는 것이 가장 나쁘다.
 */
internal object KiwoomHttpErrors {

    private val mapper = ObjectMapper()

    fun translate(response: ClientHttpResponse): KiwoomApiException {
        val status = response.statusCode
        val envelope = runCatching {
            mapper.readValue(response.body.readAllBytes(), ErrorRawResponse::class.java)
        }.getOrNull()

        val kind = envelope?.returnCode
            ?.takeIf { it != ErrorRawResponse().returnCode }
            ?.let(KiwoomErrorKind::of)
            ?: fallbackKind(status)

        return KiwoomApiException(
            returnCode = envelope?.returnCode ?: status.value(),
            returnMsg = envelope?.returnMsg ?: "HTTP ${status.value()} (본문 해석 실패)",
            kind = kind,
            operation = "HTTP ${status.value()}",
        )
    }

    private fun fallbackKind(status: HttpStatusCode): KiwoomErrorKind = when {
        status.value() == 429 -> KiwoomErrorKind.RATE_LIMITED
        status.value() == 401 || status.value() == 403 -> KiwoomErrorKind.AUTH_FAILED
        status.is4xxClientError -> KiwoomErrorKind.BAD_REQUEST
        else -> KiwoomErrorKind.OTHER
    }
}
