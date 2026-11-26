package com.stockstudy.marketdata.kiwoom.raw

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 키움 응답의 **원문 그대로**인 타입들. 이 패키지 밖으로 나가지 않는다 (하네스 G9).
 *
 * 왜 격리하는가 — 키움의 필드는 이름도(`cur_prc`) 표현도(`"+171500"`) 우리 도메인과
 * 다르다. 특히 **가격에 부호가 붙는다.** 그 부호는 가격의 일부가 아니라 전일 대비 방향
 * 표시다. 이 타입이 애플리케이션 안쪽을 돌아다니면 `"-119500"` 을 그대로 `toLong()` 한
 * 음수 가격이 언젠가 DB 에 들어간다.
 *
 * 그래서 변환을 **한 곳**(여기)에서만 하고, 밖으로는 변환을 마친 도메인 타입만 내보낸다.
 * `internal` 은 모듈 경계까지만 막아주므로, 모듈 안의 규율은 Gradle 가드가 강제한다.
 *
 * 녹화 일시 — 2026-11-26, 모의 서버(`mockapi.kiwoom.com`) 실제 응답 기준.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class TokenRawResponse(
    @JsonProperty("return_code") val returnCode: Int = UNSET_RETURN_CODE,
    @JsonProperty("return_msg") val returnMsg: String? = null,
    @JsonProperty("token") val token: String? = null,
    @JsonProperty("token_type") val tokenType: String? = null,
    /** `yyyyMMddHHmmss`, **KST**. 실측상 발급 시각 + 24시간이다. */
    @JsonProperty("expires_dt") val expiresDt: String? = null,
) {
    // data class 의 자동 toString 은 토큰 평문을 그대로 내보낸다 (ADR-022).
    override fun toString(): String =
        "TokenRawResponse(returnCode=$returnCode, returnMsg=$returnMsg, expiresDt=$expiresDt)"

    companion object {
        /** 응답에 return_code 가 아예 없을 때. 0(정상)과 섞이면 안 되므로 음수로 둔다. */
        const val UNSET_RETURN_CODE = -1
    }
}

/** ka10001 주식기본정보. 47개 필드 중 지금 쓰는 것만 선언한다 — 나머지는 무시한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class StockInfoRawResponse(
    @JsonProperty("return_code") val returnCode: Int = TokenRawResponse.UNSET_RETURN_CODE,
    @JsonProperty("return_msg") val returnMsg: String? = null,
    @JsonProperty("stk_cd") val stockCode: String? = null,
    @JsonProperty("stk_nm") val stockName: String? = null,
    /** 부호 포함 현재가. 예: `"+171500"`, `"-166900"`. 부호는 **가격이 아니다**. */
    @JsonProperty("cur_prc") val currentPrice: String? = null,
    /** 전일대비 기호 — 1 상한 / 2 상승 / 3 보합 / 4 하한 / 5 하락. */
    @JsonProperty("pre_sig") val changeSign: String? = null,
    /** 전일대비 금액. 여기서는 부호가 **의미 그대로** 금액의 방향이다. */
    @JsonProperty("pred_pre") val priceChange: String? = null,
    @JsonProperty("flu_rt") val changeRate: String? = null,
    @JsonProperty("open_pric") val openPrice: String? = null,
    @JsonProperty("high_pric") val highPrice: String? = null,
    @JsonProperty("low_pric") val lowPrice: String? = null,
    @JsonProperty("base_pric") val basePrice: String? = null,
    @JsonProperty("upl_pric") val upperLimitPrice: String? = null,
    @JsonProperty("lst_pric") val lowerLimitPrice: String? = null,
    @JsonProperty("trde_qty") val tradeVolume: String? = null,
)

/**
 * 비-2xx 응답의 공통 봉투. 유량 초과(429)처럼 상태코드로 오는 실패도 본문 형식은 같다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ErrorRawResponse(
    @JsonProperty("return_code") val returnCode: Int = TokenRawResponse.UNSET_RETURN_CODE,
    @JsonProperty("return_msg") val returnMsg: String? = null,
)

/**
 * ka10099 종목정보 리스트.
 *
 * **같은 서버인데 필드 표기법이 다르다** — ka10001 은 snake_case(`cur_prc`)인데 여기는
 * camelCase(`marketCode`)다. 봉투 필드(`return_code`)만 snake_case 로 공통이다.
 * 한 벤더 안에서도 규칙이 갈린다는 뜻이고, 그래서 필드명을 추측하지 않고 실제 응답을
 * 녹화해서 맞춘다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class InstrumentListRawResponse(
    @JsonProperty("return_code") val returnCode: Int = TokenRawResponse.UNSET_RETURN_CODE,
    @JsonProperty("return_msg") val returnMsg: String? = null,
    @JsonProperty("list") val list: List<InstrumentRaw>? = null,
)

/**
 * 종목 한 건의 원문. 15 개 필드 중 지금 쓰는 것만 선언한다.
 *
 * 쓰지 않는 것: `listCount`(상장주식수), `lastPrice`(기준가), `auditInfo`(감리구분),
 * `state`(증거금·신용), `upName`/`upSizeName`(업종·규모), `orderWarning`(투자경고),
 * `nxtEnable`(넥스트레이드 거래가능), `companyClassName`. 필요해지면 여기서 끌어올린다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class InstrumentRaw(
    @JsonProperty("code") val code: String? = null,
    @JsonProperty("name") val name: String? = null,
    /** 거래소가 아니라 **상품 종류**다 — 0 거래소, 8 ETF, 60 ETN, 6 리츠 … */
    @JsonProperty("marketCode") val marketCode: String? = null,
    @JsonProperty("marketName") val marketName: String? = null,
    /** 상장일 `yyyyMMdd`. 빈 문자열인 종목이 있다. */
    @JsonProperty("regDay") val registeredDay: String? = null,
)
