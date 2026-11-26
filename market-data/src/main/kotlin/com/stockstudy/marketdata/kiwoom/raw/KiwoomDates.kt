package com.stockstudy.marketdata.kiwoom.raw

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 키움의 `yyyyMMdd` 날짜 표기. 빈 문자열과 해석 불가를 모두 null 로 돌려준다. */
internal object KiwoomDates {

    private val FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun date(raw: String?): LocalDate? {
        val text = raw?.trim()?.takeIf { it.length == FORMAT_LENGTH } ?: return null
        return runCatching { LocalDate.parse(text, FORMAT) }.getOrNull()
    }

    private const val FORMAT_LENGTH = 8
}
