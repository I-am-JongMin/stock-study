package com.stockstudy.marketdata.kiwoom

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.stockstudy.domain.money.Money
import com.stockstudy.marketdata.quote.PriceChangeSign
import com.stockstudy.support.secret.Secret
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.web.client.ResourceAccessException
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * 하네스 V4 — **녹화한 진짜 응답**으로 해석이 맞는지 증명한다 (docs/harness.md).
 *
 * 픽스처는 손으로 만든 것이 아니라 2026-11-26 모의 서버에서 받은 원문이다
 * (`src/test/resources/kiwoom/`). 직접 만든 샘플로 테스트하면 "내가 상상한 키움"을
 * 검증하게 된다 — 그래서는 함정이 드러나지 않는다.
 *
 * 이 스위트가 지키는 세 가지 함정은 전부 실제 호출에서 발견한 것이다.
 */
@DisplayName("V4 — 키움 응답 계약")
class KiwoomContractTest {

    private lateinit var properties: KiwoomProperties

    @BeforeEach
    fun reset() {
        server.resetAll()
        properties = kiwoomProperties(server.baseUrl())
    }

    // ── 토큰 발급 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("expires_dt 는 KST 다 — UTC 로 읽으면 만료를 9시간 늦게 본다")
    fun tokenExpiryIsKoreanTime() {
        stubJson("/oauth2/token", fixture("token-ok.json"))

        val issued = tokenApi().issue()

        issued.issuedAt shouldBe NOW
        // "20261127210529" (KST) == 2026-11-27T12:05:29Z
        issued.expiresAt shouldBe Instant.parse("2026-11-27T12:05:29Z")
        Duration.between(issued.issuedAt, issued.expiresAt) shouldBe Duration.ofHours(24)

        server.verify(
            postRequestedFor(urlEqualTo("/oauth2/token"))
                .withRequestBody(matchingJsonPath("$.grant_type", equalTo("client_credentials")))
                .withRequestBody(matchingJsonPath("$.appkey", equalTo(APP_KEY)))
                .withRequestBody(matchingJsonPath("$.secretkey", equalTo(APP_SECRET))),
        )
    }

    @Test
    @DisplayName("expires_dt 를 해석하지 못해도 죽지 않고 보수적 수명으로 쓴다")
    fun unparsableExpiryFallsBack() {
        stubJson(
            "/oauth2/token",
            """{"return_code":0,"return_msg":"ok","token":"t","token_type":"Bearer","expires_dt":"알 수 없음"}""",
        )

        val issued = tokenApi().issue()

        Duration.between(issued.issuedAt, issued.expiresAt) shouldBe Duration.ofHours(1)
    }

    // ── 함정 1. HTTP 상태코드는 성공을 뜻하지 않는다 ───────────────────────

    @Test
    @DisplayName("HTTP 200 이어도 return_code 가 0 이 아니면 실패다 — 인증 실패가 200 으로 온다")
    fun httpOkIsNotSuccess() {
        stubJson("/oauth2/token", fixture("auth-failed.json")) // 상태코드는 200 이다

        val thrown = shouldThrow<KiwoomApiException> { tokenApi().issue() }

        thrown.returnCode shouldBe 3
        thrown.kind shouldBe KiwoomErrorKind.AUTH_FAILED
    }

    @Test
    @DisplayName("지원하지 않는 api-id 는 재시도해도 소용없는 실패로 분류한다")
    fun unsupportedApiIdIsBadRequest() {
        stubJson("/api/dostk/stkinfo", fixture("bad-api-id.json"))

        val thrown = shouldThrow<KiwoomApiException> {
            marketDataApi(FakeTokens("token-1")).stockSnapshot("005930")
        }

        thrown.kind shouldBe KiwoomErrorKind.BAD_REQUEST
    }

    // ── 함정 2. 가격에 붙은 부호 ──────────────────────────────────────────

    @Test
    @DisplayName("가격의 부호는 방향 표시다 — 절대값을 취하고 방향은 따로 읽는다")
    fun signedPricesBecomeAbsoluteMoney() {
        stubJson("/api/dostk/stkinfo", fixture("ka10001-005930.json"))

        val snapshot = marketDataApi(FakeTokens("token-1")).stockSnapshot("005930").shouldNotBeNull()

        snapshot.stockCode shouldBe "005930"
        snapshot.name shouldBe "삼성전자"
        // 원문 cur_prc = "+171500"
        snapshot.currentPrice shouldBe Money.krw(171_500)
        // 원문 lst_pric = "-119500" — 하한가이지 마이너스 가격이 아니다
        snapshot.lowerLimitPrice shouldBe Money.krw(119_500)
        snapshot.upperLimitPrice shouldBe Money.krw(221_500)
        // 원문 low_pric = "170700" — 부호가 아예 없는 필드도 섞여 있다
        snapshot.lowPrice shouldBe Money.krw(170_700)

        // 방향은 전용 필드에서 읽는다. pre_sig = "2" (상승)
        snapshot.sign shouldBe PriceChangeSign.RISE
        // 전일대비는 부호가 방향 그 자체다 — 여기서는 유지한다
        snapshot.priceChange shouldBe Money.krw(800)
        snapshot.changeRate shouldBe BigDecimal("+0.47")
        snapshot.tradeVolume shouldBe 13_966_982L
    }

    // ── 함정 3. 성공이 데이터 존재를 뜻하지 않는다 ─────────────────────────

    @Test
    @DisplayName("없는 종목도 return_code 0 으로 온다 — 빈 값을 0원으로 만들지 않는다")
    fun unknownInstrumentIsNotAPriceOfZero() {
        stubJson("/api/dostk/stkinfo", fixture("ka10001-unknown.json"))

        marketDataApi(FakeTokens("token-1")).stockSnapshot("999999") shouldBe null
    }

    // ── 토큰 무효화 대응 ──────────────────────────────────────────────────

    @Test
    @DisplayName("서버가 토큰을 거부하면 캐시를 버리고 한 번만 다시 시도한다")
    fun rejectedTokenTriggersSingleRetry() {
        server.stubFor(
            post(urlEqualTo("/api/dostk/stkinfo"))
                .withHeader("authorization", equalTo("Bearer stale-token"))
                .willReturn(jsonResponse(fixture("auth-failed.json"))),
        )
        server.stubFor(
            post(urlEqualTo("/api/dostk/stkinfo"))
                .withHeader("authorization", equalTo("Bearer fresh-token"))
                .willReturn(jsonResponse(fixture("ka10001-005930.json"))),
        )
        val tokens = FakeTokens("stale-token", "fresh-token")

        val snapshot = marketDataApi(tokens).stockSnapshot("005930").shouldNotBeNull()

        snapshot.currentPrice shouldBe Money.krw(171_500)
        tokens.invalidations shouldBe 1
    }

    @Test
    @DisplayName("무효가 영구적이면 재시도는 한 번으로 끝난다 — 무한 재발급이 원래 막으려던 사고다")
    fun retryHappensOnlyOnce() {
        stubJson("/api/dostk/stkinfo", fixture("auth-failed.json"))
        val tokens = FakeTokens("stale-token", "also-stale")

        shouldThrow<KiwoomApiException> { marketDataApi(tokens).stockSnapshot("005930") }

        tokens.invalidations shouldBe 1
        server.verify(2, postRequestedFor(urlEqualTo("/api/dostk/stkinfo")))
    }

    // ── 타임아웃 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("응답이 늦으면 읽기 타임아웃으로 끊는다 — 매달려 있는 것이 더 나쁘다")
    fun readTimeoutIsEnforced() {
        server.stubFor(
            post(urlEqualTo("/oauth2/token")).willReturn(
                jsonResponse(fixture("token-ok.json")).withFixedDelay((READ_TIMEOUT_MS * 4).toInt()),
            ),
        )

        shouldThrow<ResourceAccessException> { tokenApi().issue() }
    }

    // ── 조립 ──────────────────────────────────────────────────────────────

    private fun tokenApi() = KiwoomTokenApi(restClient(), properties, FIXED_CLOCK)

    private fun marketDataApi(tokens: AccessTokens) = KiwoomMarketDataApi(restClient(), tokens)

    private fun restClient() = KiwoomConfig().kiwoomRestClient(properties)

    private fun stubJson(path: String, body: String) {
        server.stubFor(post(urlEqualTo(path)).willReturn(jsonResponse(body)))
    }

    /** 토큰을 순서대로 내어주는 가짜. 몇 번 무효화되었는지만 센다. */
    private class FakeTokens(private vararg val tokens: String) : AccessTokens {
        var invalidations = 0
            private set

        private var index = 0

        override fun accessToken(): Secret = Secret(tokens[index.coerceAtMost(tokens.lastIndex)])

        override fun invalidate() {
            invalidations++
            index++
        }
    }

    companion object {
        private const val APP_KEY = "contract-test-app-key"
        private const val APP_SECRET = "contract-test-app-secret"
        private const val READ_TIMEOUT_MS = 400L

        /** KST 2026-11-26 21:05:29 — 픽스처를 녹화한 그 시각이다. */
        private val NOW: Instant = Instant.parse("2026-11-26T12:05:29Z")
        private val FIXED_CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)

        private lateinit var server: WireMockServer

        @BeforeAll
        @JvmStatic
        fun startServer() {
            // h2c(평문 HTTP/2) 업그레이드를 끈다. java.net.http 는 기본이 HTTP/2 라
            // 평문 포트에서 업그레이드를 시도하는데, 진짜 키움은 TLS 위 HTTP/1.1 로
            // 응답한다 — 테스트 더블만 현실과 다른 프로토콜을 쓰게 두지 않는다.
            server = WireMockServer(options().dynamicPort().http2PlainDisabled(true))
            server.start()
        }

        @AfterAll
        @JvmStatic
        fun stopServer() = server.stop()

        private fun jsonResponse(body: String) = aResponse()
            .withStatus(200) // 키움은 실패도 200 으로 답한다. 픽스처도 그대로 둔다
            .withHeader("Content-Type", "application/json;charset=UTF-8")
            .withBody(body)

        private fun fixture(name: String): String =
            checkNotNull(KiwoomContractTest::class.java.getResourceAsStream("/kiwoom/$name")) {
                "픽스처를 찾을 수 없습니다: /kiwoom/$name"
            }.bufferedReader().use { it.readText() }

        private fun kiwoomProperties(baseUrl: String) = KiwoomProperties(
            baseUrl = baseUrl,
            appKey = APP_KEY,
            appSecret = APP_SECRET,
            connectTimeout = Duration.ofMillis(500),
            readTimeout = Duration.ofMillis(READ_TIMEOUT_MS),
        )
    }
}
