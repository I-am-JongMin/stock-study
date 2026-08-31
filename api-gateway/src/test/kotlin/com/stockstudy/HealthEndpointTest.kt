package com.stockstudy

import com.stockstudy.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus

/**
 * Phase 0 완료 조건 — `GET /health` 200.
 *
 * 단순한 테스트지만 확인하는 것은 적지 않다: 앱이 실제로 뜨고, Flyway 가
 * 마이그레이션을 적용하고, PostgreSQL 과 Redis 커넥션이 살아 있어야 UP 이 나온다.
 */
class HealthEndpointTest @Autowired constructor(
    private val restTemplate: TestRestTemplate,
) : IntegrationTestBase() {

    @Test
    fun `GET health 는 200 과 UP 을 반환한다`() {
        val response = restTemplate.getForEntity("/health", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("\"status\":\"UP\"")
    }

    @Test
    fun `헬스체크는 DB 와 Redis 상태를 개별적으로 보고한다`() {
        // R5 — 어느 의존성이 죽었는지 구분되지 않으면 장애 대응을 할 수 없다.
        val body = restTemplate.getForEntity("/health", String::class.java).body

        assertThat(body).contains("db")
        assertThat(body).contains("redis")
    }
}
