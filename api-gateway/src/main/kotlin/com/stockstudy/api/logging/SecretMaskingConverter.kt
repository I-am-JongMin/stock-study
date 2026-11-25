package com.stockstudy.api.logging

import ch.qos.logback.classic.pattern.MessageConverter
import ch.qos.logback.classic.spi.ILoggingEvent

/**
 * 로그 메시지에서 비밀값 패턴을 마스킹하는 로그백 컨버터 (ADR-022, 하네스 G5 의 2차 그물).
 *
 * 1차 방어는 [com.stockstudy.support.secret.Secret] 타입이다 — 거기서 막히면 이 코드는
 * 일을 하지 않는다. 이 컨버터가 잡는 것은 타입을 우회한 실수다: 외부 API 응답 원문을
 * 통째로 로깅하거나, `reveal()` 한 평문이 섞인 문자열을 로그에 넘기는 경우.
 *
 * 패턴 기반 마스킹의 한계도 분명히 한다 — 정규식이 아는 모양만 지운다. 그래서 이것은
 * 그물이지 방어선이 아니고, 새 비밀 종류가 생기면 패턴과 하네스 V1 테스트를 함께 늘린다.
 *
 * logback-spring.xml 의 conversionRule 로 등록되어 %m 대신 %maskedMsg 로 쓰인다.
 * 하네스 V1 테스트가 "pattern 에 %maskedMsg 가 실제로 쓰이는지"까지 검증한다 —
 * 누군가 패턴을 %m 으로 되돌리면 테스트가 깨진다.
 */
class SecretMaskingConverter : MessageConverter() {

    override fun convert(event: ILoggingEvent): String = mask(super.convert(event))

    companion object {
        const val MASK = "****"

        /**
         * key-value 형태의 비밀 필드. JSON(`"appkey":"..."`), yml/properties(`app-key: ...`),
         * 환경변수 할당(`KIWOOM_APP_SECRET`) 표기를 모두 커버한다.
         */
        private val KEY_VALUE = Regex(
            """(?i)("?[\w.-]*(?:appkey|app[_-]?key|secretkey|secret[_-]?key|app[_-]?secret|secret|token|authorization|password|passwd|account[_-]?no|acnt[_-]?no|cano)"?\s*[:=]\s*"?)([^"'\s,;}{\]\[&]+)"""
        )

        /** HTTP Authorization 헤더의 Bearer 토큰. */
        private val BEARER = Regex("""(?i)(Bearer\s+)[A-Za-z0-9._~+/=-]{8,}""")

        fun mask(message: String): String {
            // Bearer 가 먼저다 — KEY_VALUE 가 "authorization=Bearer xxx" 에서 "Bearer" 만
            // 지우고 진짜 토큰을 남기는 사고를 하네스 V1 이 잡아냈다. 순서가 계약이다.
            var masked = BEARER.replace(message) { m -> m.groupValues[1] + MASK }
            masked = KEY_VALUE.replace(masked) { m -> m.groupValues[1] + MASK }
            return masked
        }
    }
}
