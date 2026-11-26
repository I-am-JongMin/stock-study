package com.stockstudy.marketdata.quote

import com.stockstudy.domain.money.Money
import java.math.BigDecimal

/**
 * 종목 한 건의 현재 상태. **키움의 모양이 아니라 우리의 모양이다** (하네스 G9).
 *
 * 가격은 전부 [Money] 이고 음수가 아니다. 방향은 [sign] 과 [priceChange] 가 말한다 —
 * 키움 원문에서 가격에 붙어 오던 `+`/`-` 는 여기 도달하기 전에 사라진다.
 */
data class StockSnapshot(
    val stockCode: String,
    val name: String,
    val currentPrice: Money,
    val sign: PriceChangeSign,
    /** 전일대비. 하락이면 음수다 — 여기서는 부호가 방향 그 자체다. */
    val priceChange: Money?,
    val changeRate: BigDecimal?,
    val openPrice: Money?,
    val highPrice: Money?,
    val lowPrice: Money?,
    val basePrice: Money?,
    val upperLimitPrice: Money?,
    val lowerLimitPrice: Money?,
    val tradeVolume: Long?,
)

/** 키움 `pre_sig` 의 의미. 모르는 값이 오면 [UNKNOWN] 으로 떨어뜨리고 버리지 않는다. */
enum class PriceChangeSign(val code: String) {
    UPPER_LIMIT("1"),
    RISE("2"),
    FLAT("3"),
    LOWER_LIMIT("4"),
    FALL("5"),
    UNKNOWN("");

    companion object {
        fun of(code: String?): PriceChangeSign =
            entries.firstOrNull { it.code == code?.trim() } ?: UNKNOWN
    }
}
