package com.stockstudy.marketdata.kiwoom

import com.fasterxml.jackson.databind.ObjectMapper
import com.stockstudy.marketdata.ratelimit.TrRateLimiter
import com.stockstudy.support.secret.Secret
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration

/**
 * 하네스 V7 — **키움이 아직 우리가 아는 모습인가** (docs/harness.md).
 *
 * `check` 에 들어가지 않는다. 이 테스트의 실패는 우리 버그가 아니라 **계약 변경 신호**이고,
 * 다른 의미의 실패를 같은 태스크에 섞으면 빌드 신뢰가 무너진다 (ADR-023).
 *
 * ```bash
 * ./gradlew liveTest      # .env 의 키를 읽어 모의서버에 실제로 붙는다. 키가 없으면 skip
 * ```
 *
 * 여기가 깨지면 할 일은 **픽스처를 다시 녹화하는 것**이고, 그러면 V4 가 깨지면서
 * 코드의 어느 해석이 틀렸는지 정확히 짚어준다. 두 스위트는 그렇게 한 쌍으로 움직인다.
 */
@Tag("live")
@DisplayName("V7 — 키움 모의서버 라이브 스모크")
class KiwoomLiveSmokeTest {

    @Test
    @DisplayName("토큰이 발급되고 만료가 24시간 뒤다")
    fun tokenIssuance() {
        val issued = liveToken()

        issued.token.blank() shouldBe false
        val lifetime = Duration.between(issued.issuedAt, issued.expiresAt)
        // §R3 의 전제. 여기가 바뀌면 선제 갱신 기준(10%)의 실제 길이가 달라진다.
        check(lifetime > Duration.ofHours(23) && lifetime <= Duration.ofHours(25)) {
            "토큰 유효기간이 24시간이라는 전제가 깨졌습니다: $lifetime — §R3 과 ADR-021 을 고쳐야 합니다"
        }
    }

    @Test
    @DisplayName("ka10001 응답의 필드 집합이 녹화 당시와 같다")
    fun contractHasNotDrifted() {
        val token = liveToken().token
        val live = restClient(requireNotNull(liveProperties())).post()
            .uri("/api/dostk/stkinfo")
            .contentType(MediaType.APPLICATION_JSON)
            .header("authorization", "Bearer ${token.reveal()}")
            .header("api-id", "ka10001")
            .body(mapOf("stk_cd" to SAMSUNG))
            .retrieve()
            .body(Map::class.java)
            .shouldNotBeNull()

        val recorded = MAPPER.readValue(
            checkNotNull(javaClass.getResourceAsStream("/kiwoom/ka10001-005930.json")),
            Map::class.java,
        )

        val added = live.keys - recorded.keys
        val removed = recorded.keys - live.keys
        check(added.isEmpty() && removed.isEmpty()) {
            """
            |ka10001 의 필드 집합이 녹화 당시와 다릅니다 — 계약이 바뀌었습니다.
            |  새로 생긴 필드: $added
            |  사라진 필드:   $removed
            |
            |할 일: 픽스처를 다시 녹화하고(V7 → V4), 해석 코드를 고칩니다.
            """.trimMargin()
        }
    }

    @Test
    @DisplayName("실제 시세를 우리 타입으로 해석한다 — 음수 가격이 나오지 않는다")
    fun realQuoteParsesIntoDomainTypes() {
        val token = liveToken().token
        val rest = restClient(requireNotNull(liveProperties()))
        val api = KiwoomMarketDataApi(rest, StaticToken(token), TrRateLimiter.Disabled)

        val snapshot = api.stockSnapshot(SAMSUNG).shouldNotBeNull()

        snapshot.stockCode shouldBe SAMSUNG
        snapshot.name shouldBe "삼성전자"
        // 부호 함정의 최종 방어선. 가격은 어떤 장세에서도 음수가 아니다.
        listOfNotNull(
            snapshot.currentPrice, snapshot.openPrice, snapshot.highPrice,
            snapshot.lowPrice, snapshot.basePrice,
            snapshot.upperLimitPrice, snapshot.lowerLimitPrice,
        ).forEach { it.isNegative shouldBe false }
    }

    /**
     * 토큰을 **스위트 전체에서 한 번만** 발급한다.
     *
     * 테스트마다 발급하면 자기 자신이 유량 제한(`1700: 유량=au10001`)에 걸린다 — 실제로
     * 첫 실행에서 429 를 받고 알았다. 발급 호출에도 빈도 제한이 있다는 §R3 의 전제가
     * 실측으로 확인된 순간이기도 하다.
     */
    private fun liveToken(): IssuedToken {
        val properties = requireNotNull(liveProperties())
        return cachedToken ?: KiwoomTokenApi(restClient(properties), properties, Clock.systemUTC())
            .issue().also { cachedToken = it }
    }

    /** 이미 받아둔 토큰을 그대로 내어준다. 라이브 스모크는 캐시·락을 검증하지 않는다. */
    private class StaticToken(private val token: Secret) : AccessTokens {
        override fun accessToken(): Secret = token
        override fun invalidate() = Unit
    }

    private fun restClient(properties: KiwoomProperties) =
        KiwoomConfig().kiwoomRestClient(properties)

    /** 키가 없으면 `null` 을 돌려주고 테스트를 건너뛴다 — 키 없는 환경에서도 태스크는 돈다. */
    private fun liveProperties(): KiwoomProperties? {
        val appKey = System.getenv("KIWOOM_APP_KEY").orEmpty()
        val appSecret = System.getenv("KIWOOM_APP_SECRET").orEmpty()
        assumeTrue(
            appKey.isNotBlank() && appSecret.isNotBlank(),
            "KIWOOM_APP_KEY / KIWOOM_APP_SECRET 이 없어 라이브 스모크를 건너뜁니다",
        )
        return KiwoomProperties(
            baseUrl = System.getenv("KIWOOM_BASE_URL") ?: MOCK_BASE_URL,
            appKey = appKey,
            appSecret = appSecret,
        )
    }

    private companion object {
        /** JVM 수명 동안 하나. 발급 유량을 아끼는 것이 목적이다. */
        @Volatile
        var cachedToken: IssuedToken? = null

        const val SAMSUNG = "005930"
        const val MOCK_BASE_URL = "https://mockapi.kiwoom.com"
        val MAPPER = ObjectMapper()
    }
}
