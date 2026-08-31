package com.stockstudy.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * 통합 테스트 기반 클래스 (§12).
 *
 * **왜 Phase 0 부터 Testcontainers 인가**
 * §06 의 동시성 기법은 mock DB 로 검증할 수 없다. 조건부 UPDATE 의 원자성,
 * UNIQUE 제약, 데드락은 실제 PostgreSQL 에서만 발생한다. Phase 3 에서 급하게
 * 붙이면 그때 이미 mock 기반 테스트가 잔뜩 쌓여 있어 되돌리기 어렵다.
 *
 * **왜 `@Container` 대신 `start()` 를 직접 호출하는가**
 * `@Container` 는 테스트 클래스마다 컨테이너를 새로 띄운다. static 필드에서 한 번만
 * 시작하면 JVM 전체에서 재사용되어(싱글턴 컨테이너 패턴) 전체 테스트 시간이 크게 줄고,
 * Phase 3 에서 동시성 테스트를 수백 번 반복 실행할 때 이 차이가 결정적이다.
 * 컨테이너는 JVM 종료 시 Testcontainers 의 Ryuk 컨테이너가 정리한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class IntegrationTestBase {

    companion object {

        @JvmStatic
        protected val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine")).apply {
                withDatabaseName("stockstudy")
                withUsername("stockstudy")
                withPassword("stockstudy")
                // docker-compose.yml 과 같은 설정으로 띄운다.
                // 테스트 환경과 로컬 환경이 다르면 동시성 버그가 한쪽에서만 재현된다.
                withCommand(
                    "postgres",
                    "-c", "log_lock_waits=on",
                    "-c", "deadlock_timeout=1s",
                )
                start()
            }

        @JvmStatic
        protected val redis: GenericContainer<Nothing> =
            GenericContainer<Nothing>(DockerImageName.parse("redis:7-alpine")).apply {
                withExposedPorts(6379)
                start()
            }

        @JvmStatic
        @DynamicPropertySource
        fun registerContainerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
        }
    }
}
