package com.stockstudy.marketdata.support

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * Redis 컨테이너 하나를 JVM 전체에서 공유한다 (api-gateway 의 IntegrationTestBase 와 같은 패턴).
 *
 * **Spring 컨텍스트를 띄우지 않는 이유** — 여기서 검증하는 것은 분산락이라는 작은 부품이고,
 * 부품은 프레임워크 없이도 돌아가야 한다. 컨텍스트 기동 비용(수 초)을 빼면 동시성 테스트를
 * 수백 번 반복해도 부담이 없다. Phase 3 에서 이 반복 실행이 결정적이다.
 */
object RedisTestSupport {

    private val container: GenericContainer<Nothing> =
        GenericContainer<Nothing>(DockerImageName.parse("redis:7-alpine")).apply {
            withExposedPorts(6379)
            start()
        }

    val template: StringRedisTemplate by lazy {
        val factory = LettuceConnectionFactory(container.host, container.getMappedPort(6379))
        factory.afterPropertiesSet()
        StringRedisTemplate(factory).apply { afterPropertiesSet() }
    }

    /** 테스트 간 격리. 키 공간이 좁아 테스트끼리 충돌하기 쉽다. */
    fun flush() {
        template.connectionFactory!!.connection.use { it.serverCommands().flushAll() }
    }
}
