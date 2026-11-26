package com.stockstudy.marketdata.kiwoom.raw

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 요청 본문도 키움의 어휘다 — 응답과 같은 이유로 이 패키지 안에 둔다 (하네스 G9).
 *
 * 문자열 맵(`mapOf("stk_cd" to ...)`) 으로 만들면 벤더 필드명이 애플리케이션 코드
 * 아무 데나 퍼진다. 어휘가 한 곳에 모여 있어야 키움을 바꿀 때 바꿀 곳이 한 곳이다.
 */
internal class TokenRawRequest(
    @JsonProperty("appkey") val appKey: String,
    @JsonProperty("secretkey") val secretKey: String,
) {
    @JsonProperty("grant_type")
    val grantType: String = "client_credentials"

    // 평문 자격증명을 담고 있다. 로그로 새지 않도록 toString 을 막는다 (ADR-022).
    override fun toString(): String = "TokenRawRequest(****)"
}

internal data class StockInfoRawRequest(
    @JsonProperty("stk_cd") val stockCode: String,
)
