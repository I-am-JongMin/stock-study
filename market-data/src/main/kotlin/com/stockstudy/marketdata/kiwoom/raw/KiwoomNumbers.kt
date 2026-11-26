package com.stockstudy.marketdata.kiwoom.raw

import com.stockstudy.domain.money.Money
import java.math.BigDecimal

/**
 * 키움 문자열 숫자 → 도메인 타입 변환. **부호 함정이 여기서 끝난다** (하네스 G9).
 *
 * 실측으로 확인한 표기 규칙 (2026-11-26, 삼성전자 005930):
 *
 * ```
 * cur_prc   = "+171500"   // 상승 중 → 가격은 171,500 원
 * low_pric  = "170700"    // 부호 없음
 * lst_pric  = "-119500"   // 하한가 → 가격은 119,500 원
 * pred_pre  = "+800"      // 전일대비 → 여기서는 부호가 방향 그 자체
 * ```
 *
 * 즉 **같은 `+`/`-` 가 필드에 따라 다른 뜻**이다. 가격 필드에서는 방향 표시이고,
 * 대비 필드에서는 부호다. 호출부가 매번 판단하게 두면 언젠가 틀린다. 그래서
 * [price] 와 [signedAmount] 로 함수를 갈라, 이름이 의미를 고정하게 한다.
 */
internal object KiwoomNumbers {

    /** 가격. 부호는 방향 표시이므로 버리고 절대값을 취한다. */
    fun price(raw: String?): Money? = raw.toLongOrNull()?.let { Money.krw(Math.abs(it)) }

    /** 전일대비처럼 부호가 방향 그 자체인 금액. 부호를 **유지**한다. */
    fun signedAmount(raw: String?): Money? = raw.toLongOrNull()?.let { Money.krw(it) }

    fun quantity(raw: String?): Long? = raw.toLongOrNull()

    fun rate(raw: String?): BigDecimal? = raw.blankToNull()?.let { runCatching { BigDecimal(it) }.getOrNull() }

    /**
     * 빈 문자열은 null 이다. 키움은 값이 없으면 `""` 를 보낸다 — 없는 종목을 조회해도
     * `return_code: 0` 과 함께 전 필드가 `""` 로 온다. 0 으로 바꿔 넣으면 "현재가 0원"
     * 이라는 거짓이 만들어진다.
     */
    private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun String?.toLongOrNull(): Long? =
        blankToNull()?.let { runCatching { it.toLong() }.getOrNull() }
}
