package com.stockstudy.marketdata.instrument

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * 종목마스터 배선. 부품은 애너테이션 없는 평범한 클래스로 두고 조립만 여기서 한다
 * (키움 쪽과 같은 방침 — 테스트가 Spring 없이 부품을 직접 조립할 수 있어야 한다).
 */
@Configuration
@EnableConfigurationProperties(BoundInstrumentSyncProperties::class)
class InstrumentConfig {

    @Bean
    fun instrumentRepository(jdbc: JdbcClient, batchJdbc: NamedParameterJdbcTemplate) =
        InstrumentRepository(jdbc, batchJdbc)

    @Bean
    fun transactionTemplate(transactionManager: PlatformTransactionManager) =
        TransactionTemplate(transactionManager)

    @Bean
    fun instrumentSyncService(
        source: InstrumentSource,
        repository: InstrumentRepository,
        transactions: TransactionTemplate,
        clock: Clock,
        bound: BoundInstrumentSyncProperties,
    ) = InstrumentSyncService(source, repository, transactions, clock, bound.toProperties())
}

/**
 * 설정 바인딩 전용 타입.
 *
 * [InstrumentSyncProperties] 를 직접 `@ConfigurationProperties` 로 만들지 않는 이유 —
 * 그 타입은 순수 값이어야 테스트가 한 줄로 만들어 쓴다. 바인딩 애너테이션이 붙는 순간
 * 프레임워크 타입이 되고, 기본값·검증이 두 군데(생성자와 yml)로 갈린다.
 */
@ConfigurationProperties(prefix = "instrument-sync")
class BoundInstrumentSyncProperties(
    val minInstrumentCount: Int = 100,
    val maxDelistRatio: Double = 0.05,
) {
    fun toProperties() = InstrumentSyncProperties(minInstrumentCount, maxDelistRatio)
}
