package com.stockstudy.marketdata.kiwoom.raw

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `expires_dt` 해석. 키움은 `"20261127210529"` 처럼 **KST 기준 `yyyyMMddHHmmss`** 를 준다.
 *
 * 실측 근거 (2026-11-26) — 발급 응답의 `expires_dt` 가 `20261127210529` 였고 같은 요청의
 * 서버 `Date` 헤더가 `Thu, 26 Nov 2026 12:05:37 GMT`(= KST 21:05:37) 였다. 타임존 표기가
 * 없는 시각을 UTC 로 읽으면 만료를 9시간 늦게 보고, 그만큼 **죽은 토큰을 쓰게 된다.**
 */
internal object KiwoomTokenExpiry {

    private val FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val KST = ZoneId.of("Asia/Seoul")

    /** 해석 불가·과거 시각이면 null. 호출부가 보수적인 기본값으로 대체한다. */
    fun parse(expiresDt: String?, issuedAt: Instant): Instant? {
        val text = expiresDt?.trim()?.takeIf { it.length == FORMAT_LENGTH } ?: return null
        val parsed = runCatching { LocalDateTime.parse(text, FORMAT).atZone(KST).toInstant() }
            .getOrNull() ?: return null
        return parsed.takeIf { it.isAfter(issuedAt) }
    }

    /**
     * 만료를 해석하지 못했을 때 쓰는 수명.
     *
     * 발급 자체는 성공했으므로 토큰은 쓸 수 있다 — 잃은 것은 "언제까지인가"뿐이다.
     * 기동을 실패시키는 대신 짧게 잡고 자주 갱신한다. 24시간짜리를 1시간으로 쓰는 낭비는
     * 하루 24번 발급이고, 그 정도는 레이트 리밋 안에서 감당된다.
     */
    val CONSERVATIVE_LIFETIME: Duration = Duration.ofHours(1)

    private const val FORMAT_LENGTH = 14
}
