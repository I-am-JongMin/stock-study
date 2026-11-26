package com.stockstudy.marketdata.kiwoom

import com.stockstudy.marketdata.lock.RedisDistributedLock
import com.stockstudy.marketdata.ratelimit.RedisTrRateLimiter
import com.stockstudy.marketdata.ratelimit.TrRateLimiter
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Clock

/**
 * 키움 클라이언트 배선.
 *
 * [Clock] 은 여기서 만들지 않는다. 애플리케이션이 이미 소유한 빈이고(§01 함정 3), 설정
 * 클래스마다 하나씩 만들면 "어느 시계가 진짜인가"가 모호해진다. 실제로 중복 정의로
 * 컨텍스트 기동이 실패하면서 드러났다.
 *
 * 부품들(락·캐시·발급·조회)은 전부 애너테이션 없는 평범한 클래스다. 배선만 여기 모은다 —
 * 그래야 테스트가 Spring 컨텍스트 없이 부품을 직접 조립할 수 있고, 동시성 검증을
 * 수백 번 반복해도 비용이 들지 않는다.
 */
@Configuration
@EnableConfigurationProperties(KiwoomProperties::class)
class KiwoomConfig {

    @Bean
    fun redisDistributedLock(redis: StringRedisTemplate): RedisDistributedLock =
        RedisDistributedLock(redis)

    @Bean
    fun kiwoomRestClient(properties: KiwoomProperties): RestClient {
        // java.net.http 기반 — 가상 스레드에서 블로킹해도 캐리어를 잡지 않는다 (ADR-003).
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout)
            .build()
        val factory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(properties.readTimeout)
        }
        return RestClient.builder()
            .baseUrl(properties.baseUrl)
            .requestFactory(factory)
            // 비-2xx 도 키움의 봉투로 번역한다. 같은 성질의 실패가 두 타입으로 갈리면
            // 호출부의 분기가 반드시 한쪽을 빠뜨린다 (V7 이 429 로 알려줬다).
            .defaultStatusHandler(HttpStatusCode::isError) { _, response ->
                throw KiwoomHttpErrors.translate(response)
            }
            .build()
    }

    @Bean
    fun kiwoomTokenApi(
        kiwoomRestClient: RestClient,
        properties: KiwoomProperties,
        clock: Clock,
    ): KiwoomTokenApi = KiwoomTokenApi(kiwoomRestClient, properties, clock)

    @Bean
    fun kiwoomTokenProvider(
        redis: StringRedisTemplate,
        lock: RedisDistributedLock,
        issuer: KiwoomTokenApi,
        properties: KiwoomProperties,
        clock: Clock,
    ): KiwoomTokenProvider {
        if (!properties.credentialsConfigured()) {
            // 죽이지 않는다 — 키 없이도 앱이 떠야 DB·헬스체크 같은 나머지를 개발할 수 있다.
            // 대신 조용히 넘어가지도 않는다. 8005 를 보고 나서 원인을 찾게 두지 않는다.
            log.warn(
                "KIWOOM_APP_KEY / KIWOOM_APP_SECRET 이 비어 있습니다. " +
                    "키움 호출은 전부 인증 실패합니다 — docs/next-steps.md §3.1 참고",
            )
        }
        return KiwoomTokenProvider(redis, lock, issuer, properties, clock)
    }

    @Bean
    fun trRateLimiter(redis: StringRedisTemplate, properties: KiwoomProperties): TrRateLimiter =
        RedisTrRateLimiter(
            redis = redis,
            keyPrefix = properties.rateLimit.keyPrefix,
            interval = properties.rateLimit.interval,
            maxWait = properties.rateLimit.maxWait,
        )

    @Bean
    fun kiwoomMarketDataApi(
        kiwoomRestClient: RestClient,
        tokenProvider: KiwoomTokenProvider,
        rateLimiter: TrRateLimiter,
    ): KiwoomMarketDataApi = KiwoomMarketDataApi(kiwoomRestClient, tokenProvider, rateLimiter)

    private companion object {
        val log = LoggerFactory.getLogger(KiwoomConfig::class.java)
    }
}
