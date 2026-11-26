package com.stockstudy.marketdata.kiwoom

import com.stockstudy.marketdata.instrument.Instrument
import com.stockstudy.marketdata.instrument.MarketType
import com.stockstudy.marketdata.instrument.SecurityType
import com.stockstudy.marketdata.kiwoom.raw.InstrumentListRawRequest
import com.stockstudy.marketdata.kiwoom.raw.InstrumentListRawResponse
import com.stockstudy.marketdata.kiwoom.raw.KiwoomDates
import com.stockstudy.marketdata.kiwoom.raw.KiwoomNumbers
import com.stockstudy.marketdata.kiwoom.raw.StockInfoRawRequest
import com.stockstudy.marketdata.kiwoom.raw.StockInfoRawResponse
import com.stockstudy.marketdata.kiwoom.raw.TokenRawResponse
import com.stockstudy.marketdata.quote.PriceChangeSign
import com.stockstudy.marketdata.quote.StockSnapshot
import com.stockstudy.marketdata.ratelimit.TrRateLimiter
import com.stockstudy.support.secret.Secret
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

/**
 * 키움 시세 조회. 지금은 ka10001(주식기본정보) 하나다.
 *
 * **응답 해석의 3대 함정** — 전부 2026-11-26 모의 서버 실측에서 확인했다.
 *
 * 1. **HTTP 200 이 성공이 아니다.** 인증 실패도 200 으로 오고 본문의 `return_code` 가 3 이다.
 * 2. **`return_code: 0` 이 "데이터가 있다"는 뜻이 아니다.** 없는 종목코드를 넣으면 0 과 함께
 *    47개 필드가 전부 `""` 로 온다. 빈 값을 0 으로 바꿔 넣으면 "현재가 0원"이라는 거짓이 생긴다.
 * 3. **가격에 부호가 붙는다.** 그 부호는 방향 표시다 — [KiwoomNumbers] 에서 끝난다.
 */
class KiwoomMarketDataApi(
    private val rest: RestClient,
    private val tokens: AccessTokens,
    private val rateLimiter: TrRateLimiter,
) {

    /**
     * @return 없는 종목이면 `null`. 키움은 이 경우에도 성공으로 답하므로 여기서 구분한다.
     */
    fun stockSnapshot(stockCode: String): StockSnapshot? {
        require(stockCode.isNotBlank()) { "종목코드가 비어 있습니다" }

        // 두 겹의 의미가 겹치는 지점이다. 안쪽(레이트 리밋)은 "지금 쏴도 되는가",
        // 바깥(재시도)은 "쏜 결과가 인증 실패인가". 재시도도 또 하나의 호출이므로
        // 레이트 리밋 안쪽에 들어간다.
        val raw = withAuthRetry(API_ID_STOCK_INFO) { token ->
            rateLimiter.withPermit(API_ID_STOCK_INFO) { fetchStockInfo(token, stockCode) }
        }

        val code = raw.stockCode?.trim().orEmpty()
        if (code.isEmpty()) {
            log.debug("ka10001 — 조회 결과 없음: 요청 종목코드={}", stockCode)
            return null
        }

        val currentPrice = KiwoomNumbers.price(raw.currentPrice)
            ?: throw KiwoomApiException(
                raw.returnCode,
                "종목($code)은 있는데 현재가가 비어 있습니다. 값 없음을 0원으로 바꾸지 않습니다.",
                KiwoomErrorKind.OTHER, "ka10001",
            )

        return StockSnapshot(
            stockCode = code,
            name = raw.stockName?.trim().orEmpty(),
            currentPrice = currentPrice,
            sign = PriceChangeSign.of(raw.changeSign),
            priceChange = KiwoomNumbers.signedAmount(raw.priceChange),
            changeRate = KiwoomNumbers.rate(raw.changeRate),
            openPrice = KiwoomNumbers.price(raw.openPrice),
            highPrice = KiwoomNumbers.price(raw.highPrice),
            lowPrice = KiwoomNumbers.price(raw.lowPrice),
            basePrice = KiwoomNumbers.price(raw.basePrice),
            upperLimitPrice = KiwoomNumbers.price(raw.upperLimitPrice),
            lowerLimitPrice = KiwoomNumbers.price(raw.lowerLimitPrice),
            tradeVolume = KiwoomNumbers.quantity(raw.tradeVolume),
        )
    }

    /**
     * ka10099 — 한 거래소의 **종목 전체**를 한 번에 받는다 (§F1.1).
     *
     * **이 TR 하나가 §3.3 의 설계를 바꿨다.** ka10001 로 종목별 조회를 하면 2,800 회 ×
     * 초당 1 회 = 최소 50 분이고, 50 분짜리 배치는 반드시 중간에 죽는다. ka10099 는
     * 유가증권 2,496 건을 **호출 한 번**에 주고 `cont-yn` 도 `N` 이라 페이징조차 없다.
     * 한도가 설계를 바꾼다는 ADR-025 의 결론이 이렇게 적용된다.
     *
     * 응답에는 주식뿐 아니라 ETF·ETN·리츠·펀드가 섞여 있다. 걸러내지 않고 [SecurityType]
     * 으로 구분해서 전부 돌려준다 — 무엇을 쓸지는 저장·조회 쪽이 정한다 (ADR-026).
     */
    fun instrumentList(market: MarketType): List<Instrument> {
        val raw = withAuthRetry(API_ID_INSTRUMENT_LIST) { token ->
            rateLimiter.withPermit(API_ID_INSTRUMENT_LIST) { fetchInstrumentList(token, market) }
        }

        val rows = raw.list.orEmpty()
        val instruments = rows.mapNotNull { row ->
            val code = row.code?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val securityType = SecurityType.of(row.marketCode)
            if (securityType == SecurityType.OTHER) {
                // 조용히 버리지 않는다. 키움이 새 분류를 추가했다는 신호이고,
                // 그 사실을 아무도 모르는 것이 틀린 분류보다 나쁘다.
                log.warn(
                    "ka10099 — 알 수 없는 marketCode 입니다: code={} marketCode={} marketName={}",
                    code, row.marketCode, row.marketName,
                )
            }
            Instrument(
                code = code,
                market = market,
                securityType = securityType,
                name = row.name?.trim().orEmpty(),
                listedAt = KiwoomDates.date(row.registeredDay),
            )
        }

        log.info("ka10099 — {} 종목 {}건 수신 (원문 {}건)", market, instruments.size, rows.size)
        return instruments
    }

    private fun fetchInstrumentList(token: Secret, market: MarketType): InstrumentListRawResponse {
        val response = rest.post()
            .uri("/api/dostk/stkinfo")
            .contentType(MediaType.APPLICATION_JSON)
            .header("authorization", "Bearer ${token.reveal()}")
            .header("api-id", API_ID_INSTRUMENT_LIST)
            .body(InstrumentListRawRequest(market.kiwoomMarketType))
            .retrieve()
            .body<InstrumentListRawResponse>()
            ?: throw KiwoomApiException(
                TokenRawResponse.UNSET_RETURN_CODE, "응답 본문이 비어 있습니다",
                KiwoomErrorKind.OTHER, API_ID_INSTRUMENT_LIST,
            )

        if (response.returnCode != 0) {
            throw KiwoomApiException(
                response.returnCode, response.returnMsg,
                KiwoomErrorKind.of(response.returnCode), API_ID_INSTRUMENT_LIST,
            )
        }
        return response
    }

    /**
     * HTTP 왕복과 응답 봉투 판정.
     *
     * `return_code` 판정이 반드시 이 안에 있어야 한다. 바깥에 두면 인증 실패를 재시도가
     * 영영 보지 못한다 — 하네스 V4 가 첫 실행에서 잡아낸 실수다.
     */
    private fun fetchStockInfo(token: Secret, stockCode: String): StockInfoRawResponse {
        val response = rest.post()
            .uri("/api/dostk/stkinfo")
            .contentType(MediaType.APPLICATION_JSON)
            // 평문 토큰이 나가는 지점 (ADR-022). grep "reveal()" 로 열거된다.
            .header("authorization", "Bearer ${token.reveal()}")
            .header("api-id", API_ID_STOCK_INFO)
            .body(StockInfoRawRequest(stockCode))
            .retrieve()
            .body<StockInfoRawResponse>()
            ?: throw KiwoomApiException(
                TokenRawResponse.UNSET_RETURN_CODE, "응답 본문이 비어 있습니다",
                KiwoomErrorKind.OTHER, API_ID_STOCK_INFO,
            )

        if (response.returnCode != 0) {
            throw KiwoomApiException(
                response.returnCode, response.returnMsg,
                KiwoomErrorKind.of(response.returnCode), API_ID_STOCK_INFO,
            )
        }
        return response
    }

    /**
     * 서버가 "토큰이 무효하다"(8005) 고 답하면 캐시를 버리고 **한 번만** 다시 시도한다.
     *
     * 만료 시각만으로는 알 수 없는 무효화가 있다 — 포털에서 키를 재발급했거나, IP 등록이
     * 바뀌었거나, 서버 쪽에서 세션을 끊었을 때. 서버의 말을 신호로 삼는 쪽이 정확하다.
     * 재시도를 한 번으로 묶는 이유는, 무효가 영구적일 때 무한 재발급으로 레이트 리밋을
     * 때리는 것이 원래 막으려던 사고 그 자체이기 때문이다.
     */
    private fun <T> withAuthRetry(operation: String, call: (Secret) -> T): T =
        try {
            call(tokens.accessToken())
        } catch (e: KiwoomApiException) {
            if (e.kind != KiwoomErrorKind.AUTH_FAILED) throw e
            log.warn("{} — 서버가 토큰을 거부했습니다. 캐시를 버리고 1회 재시도합니다: {}", operation, e.returnMsg)
            tokens.invalidate()
            call(tokens.accessToken())
        }

    private companion object {
        const val API_ID_STOCK_INFO = "ka10001"
        const val API_ID_INSTRUMENT_LIST = "ka10099"
        val log = LoggerFactory.getLogger(KiwoomMarketDataApi::class.java)
    }
}
