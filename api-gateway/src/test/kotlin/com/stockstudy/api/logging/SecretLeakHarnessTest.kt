package com.stockstudy.api.logging

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.util.LogbackMDCAdapter
import com.fasterxml.jackson.databind.ObjectMapper
import com.stockstudy.support.secret.Secret
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * 하네스 V1 — 비밀값 유출 경로를 카나리아로 일부러 공격한다 (ADR-022, docs/harness.md §3).
 *
 * Secret 타입 자체의 보장(toString·보간·컬렉션)은 core-support 의 SecretTest 에 있다.
 * 여기서는 api-gateway 가 가진 의존성이 필요한 경로를 검증한다:
 * Jackson 직렬화 fail-fast, 마스킹 컨버터, 그리고 logback 설정이 실제로 그 컨버터를 쓰는지.
 */
class SecretLeakHarnessTest {

    private val canaryKey = "CANARY-mYkymXDa4vGyUUs11cpKVhwD"
    private val canaryToken = "CANARYtokenWv6x9EaBcDeFgHiJkLmNoPqRsTuVwXyZ012345"

    /**
     * Jackson 2.18 까지는 프로퍼티 없는 빈이 fail-fast(InvalidDefinitionException)였지만
     * 2.19 부터 기본값이 바뀌어 빈 객체 `{}` 로 직렬화된다. 어느 쪽이든 **평문은 절대
     * 나가지 않는다** — 그것이 이 하네스가 지키는 보장이고, 버전 기본값에 기대지 않도록
     * "예외 또는 평문 부재" 둘 다를 허용하되 평문 부재만은 무조건 검증한다.
     */
    @Nested
    inner class `Jackson 직렬화는 어떤 경우에도 평문을 내보내지 않는다` {

        @Test
        fun `Secret 단독 직렬화 - 평문 부재`() {
            val result = runCatching { ObjectMapper().writeValueAsString(Secret(canaryKey)) }
            result.getOrNull()?.let { it shouldNotContain canaryKey }
            result.exceptionOrNull()?.let { (it.message ?: "") shouldNotContain canaryKey }
        }

        @Test
        fun `Secret 을 품은 설정 객체 직렬화 - 응답 DTO 에 섞이는 실수를 해도 평문 부재`() {
            data class KiwoomConfig(val baseUrl: String, val appKey: Secret)

            val result = runCatching {
                ObjectMapper().writeValueAsString(
                    KiwoomConfig("https://mockapi.kiwoom.com", Secret(canaryKey)),
                )
            }
            result.getOrNull()?.let {
                it shouldNotContain canaryKey
                it shouldContain "mockapi" // 비밀이 아닌 필드는 정상 직렬화된다
            }
            result.exceptionOrNull()?.let { (it.message ?: "") shouldNotContain canaryKey }
        }
    }

    @Nested
    inner class `마스킹 컨버터는 타입을 우회한 평문을 지운다` {

        @Test
        fun `키움 토큰 응답 원문을 통째로 로깅해도 토큰이 지워진다`() {
            val raw = """응답: {"token_type":"Bearer","token":"$canaryToken","expires_dt":"20261126205105"}"""
            val masked = SecretMaskingConverter.mask(raw)
            masked shouldNotContain canaryToken
            masked shouldContain "expires_dt" // 비밀이 아닌 필드는 남는다
        }

        @Test
        fun `토큰 발급 요청 본문의 appkey·secretkey 가 지워진다`() {
            val raw = """{"grant_type":"client_credentials","appkey":"$canaryKey","secretkey":"$canaryToken"}"""
            val masked = SecretMaskingConverter.mask(raw)
            masked shouldNotContain canaryKey
            masked shouldNotContain canaryToken
            masked shouldContain "client_credentials"
        }

        @Test
        fun `Authorization 헤더의 Bearer 토큰이 지워진다`() {
            val masked = SecretMaskingConverter.mask("headers={authorization=Bearer $canaryToken}")
            masked shouldNotContain canaryToken
        }

        @Test
        fun `환경변수·yml 표기의 비밀 할당이 지워진다`() {
            SecretMaskingConverter.mask("KIWOOM_APP_SECRET=$canaryKey") shouldNotContain canaryKey
            SecretMaskingConverter.mask("kiwoom.app-key: $canaryKey") shouldNotContain canaryKey
            SecretMaskingConverter.mask("accountNo=67528985") shouldNotContain "67528985"
        }

        @Test
        fun `비밀이 아닌 평범한 메시지는 건드리지 않는다`() {
            val msg = "종목 005930 현재가 166900, 체결 22046619건 처리"
            SecretMaskingConverter.mask(msg) shouldContain msg
        }
    }

    @Nested
    inner class `logback 설정이 실제로 마스킹을 거친다` {

        /**
         * logback-spring.xml 을 실제 LoggerContext 로 구성하고 콘솔 출력을 가로채
         * 카나리아가 지워지는지 끝까지 확인한다 — "컨버터는 맞는데 설정이 안 물려 있는"
         * 종류의 구멍(패턴을 %m 으로 되돌리는 실수)을 잡는다.
         */
        @Test
        fun `카나리아를 로깅하면 콘솔 출력에는 마스킹된 값만 남는다`() {
            val captured = ByteArrayOutputStream()
            val originalOut = System.out
            val context = LoggerContext().apply {
                // SLF4J 바인딩이 해주는 초기화 — 맨손 컨텍스트에는 없어서 NPE 가 난다
                mdcAdapter = LogbackMDCAdapter()
            }
            try {
                System.setOut(PrintStream(captured, true, Charsets.UTF_8))
                JoranConfigurator().apply {
                    setContext(context)
                    javaClass.classLoader.getResourceAsStream("logback-spring.xml").use { doConfigure(it) }
                }
                context.getLogger("leak.probe").info("토큰 수신: token=$canaryToken")
            } finally {
                System.setOut(originalOut)
                context.stop()
            }

            val output = captured.toString(Charsets.UTF_8)
            output shouldContain "토큰 수신"       // 로그 자체는 찍혔고
            output shouldNotContain canaryToken    // 비밀만 지워졌다
            output shouldContain SecretMaskingConverter.MASK
        }
    }
}
