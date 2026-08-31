package com.stockstudy.domain.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 통화. [scale] 은 최소 단위의 소수점 자리수다.
 *
 * KRW 는 0 — 최소 단위가 1원이므로 소수점이 없다.
 * USD 는 2 — 최소 단위가 1센트다.
 *
 * §13 에서 미국 주식을 붙일 때 여기에 통화가 추가된다. 그때 [Money] 산술 코드는
 * 한 줄도 바뀌지 않아야 한다. 바뀐다면 추상화가 틀렸다는 신호다.
 */
enum class Currency(val scale: Int, val symbol: String) {
    KRW(scale = 0, symbol = "₩"),
    USD(scale = 2, symbol = "$"),
}

/**
 * 금액. 내부는 **최소 단위 정수**([minorUnits])로만 보관한다.
 *
 * `Double` 을 쓰지 않는 이유는 `0.1 + 0.2 != 0.3` 이기 때문이고 (§01 함정 2),
 * `BigDecimal` 을 내부 표현으로 쓰지 않는 이유는 scale 이 서로 다른 값들이
 * 섞이면서 `compareTo` 와 `equals` 가 갈라지기 때문이다
 * (`BigDecimal("1.0") != BigDecimal("1.00")`).
 *
 * 정수 한 개로 표현하면 등가성·비교·해시가 전부 일관되고,
 * §06 C6 에서 총매입금액을 원자적으로 더할 때 DB 의 `BIGINT` 와 1:1 로 대응된다.
 *
 * 모든 산술은 오버플로 시 조용히 감싸지 않고 [ArithmeticException] 으로 실패한다.
 * 잔고 계산이 조용히 틀리는 것보다 시끄럽게 죽는 것이 낫다.
 */
data class Money(
    val minorUnits: Long,
    val currency: Currency,
) : Comparable<Money> {

    val isZero: Boolean get() = minorUnits == 0L
    val isNegative: Boolean get() = minorUnits < 0L
    val isPositive: Boolean get() = minorUnits > 0L

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.addExact(minorUnits, other.minorUnits), currency)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.subtractExact(minorUnits, other.minorUnits), currency)
    }

    /** 단가 × 수량. 수량은 통화가 없는 순수 개수이므로 [Money] 가 아니다. */
    operator fun times(quantity: Long): Money =
        Money(Math.multiplyExact(minorUnits, quantity), currency)

    operator fun unaryMinus(): Money =
        Money(Math.negateExact(minorUnits), currency)

    override fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return minorUnits.compareTo(other.minorUnits)
    }

    /** 표시·직렬화 용도. 계산에는 쓰지 않는다. */
    fun toBigDecimal(): BigDecimal = BigDecimal.valueOf(minorUnits, currency.scale)

    override fun toString(): String = "${currency.symbol}${toBigDecimal().toPlainString()}"

    private fun requireSameCurrency(other: Money) {
        require(currency == other.currency) {
            "통화가 다른 금액은 연산할 수 없습니다: $currency vs ${other.currency}"
        }
    }

    companion object {
        fun krw(won: Long): Money = Money(won, Currency.KRW)

        fun zero(currency: Currency): Money = Money(0L, currency)

        /**
         * 사람이 읽는 금액([amount])을 [Money] 로 바꾼다.
         *
         * 해당 통화의 최소 단위보다 작은 자리가 있으면 **버리지 않고 예외를 던진다**
         * (`RoundingMode.UNNECESSARY`). 0.5원을 조용히 0원이나 1원으로 만들면
         * 원장 합계가 어긋나고, 그 원인을 나중에 찾는 것은 거의 불가능하다.
         */
        fun of(amount: BigDecimal, currency: Currency): Money {
            val scaled = amount.setScale(currency.scale, RoundingMode.UNNECESSARY)
            return Money(scaled.movePointRight(currency.scale).longValueExact(), currency)
        }
    }
}
