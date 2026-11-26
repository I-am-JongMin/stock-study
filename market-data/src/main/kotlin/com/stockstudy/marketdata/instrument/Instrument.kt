package com.stockstudy.marketdata.instrument

import java.time.LocalDate

/**
 * 종목마스터 한 건 (§F1.1). 키움의 모양이 아니라 우리의 모양이다 (하네스 G9).
 *
 * **키움이 주는 필드 중 일부만 올라온다.** ka10099 는 상장주식수·감리구분·신용가능
 * 여부·NXT 거래가능 등 15 개 필드를 주지만, 여기 있는 것은 `instrument` 테이블이 쓰는
 * 것뿐이다. 쓰지 않는 값을 도메인 타입에 미리 올려두면 "이건 어디서 쓰지?"를 영원히
 * 되묻게 된다. 필요해지는 시점에 raw 에서 끌어올린다.
 */
data class Instrument(
    val code: String,
    val market: MarketType,
    val securityType: SecurityType,
    val name: String,
    /** 상장일. 키움 `regDay` 가 비어 있는 종목이 있어 nullable 이다. */
    val listedAt: LocalDate?,
)

/**
 * 거래소 구분.
 *
 * **응답이 아니라 요청에서 정한다.** ka10099 는 `mrkt_tp` 로 시장을 물어보고, 응답의
 * `marketCode` 는 거래소가 아니라 **상품 종류**(ETF·ETN·리츠…)를 섞어서 알려준다.
 * 실제로 `mrkt_tp = "0"` 의 응답에는 marketCode 가 0·2·4·6·8·60·70·90 으로 흩어져 있다.
 * 우리가 무엇을 물어봤는지는 우리가 확실히 아는 사실이므로, 그쪽을 신뢰한다.
 */
enum class MarketType(val kiwoomMarketType: String) {
    KOSPI("0"),
    KOSDAQ("10"),
}

/**
 * 상품 종류. 키움 `marketCode` 로 판정한다 (ADR-026).
 *
 * 구분이 필요한 이유는 초성 검색이다 — 유가증권시장 응답에는 주식 910 건보다 ETF 가
 * 1,192 건 더 많아서, 구분 없이 담으면 `ㅌㅇㄱ` 검색이 TIGER 시리즈로 뒤덮인다.
 */
enum class SecurityType {
    STOCK,
    ETF,
    ETN,
    REIT,
    FUND,

    /** 키움이 새 분류를 추가했을 때 떨어지는 자리. 버리지 않고 담되 경고를 남긴다. */
    OTHER;

    companion object {
        /** 2026-11-26 유가증권·코스닥 응답 전수에서 관측한 `marketCode` 분포 기준. */
        private val BY_MARKET_CODE = mapOf(
            "0" to STOCK,   // 거래소
            "10" to STOCK,  // 코스닥
            "8" to ETF,
            "60" to ETN,    // ETN
            "90" to ETN,    // ETN(변동성)
            "70" to ETN,    // ETN(손실제한)
            "6" to REIT,
            "2" to FUND,    // 인프라투자금융
            "4" to FUND,    // 뮤추얼펀드
        )

        fun of(marketCode: String?): SecurityType = BY_MARKET_CODE[marketCode?.trim()] ?: OTHER
    }
}
