package com.stockstudy

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock
import java.util.TimeZone

/**
 * 이 클래스가 `com.stockstudy` 패키지에 있는 것은 의도된 배치다.
 * 컴포넌트 스캔 루트가 되어 `com.stockstudy.marketdata`, `com.stockstudy.trading`,
 * `com.stockstudy.notification` 이 자동으로 스캔 범위에 들어온다.
 * 모듈마다 `@ComponentScan` 을 손으로 추가할 필요가 없다.
 */
@SpringBootApplication
class StockStudyApplication {

    /**
     * [Clock] 을 빈으로 노출한다 (§01 함정 3).
     *
     * 도메인 코드는 `LocalDateTime.now()` 를 절대 직접 호출하지 않고 이 빈을 주입받는다.
     * 그래야 테스트에서 `Clock.fixed()` 로 갈아끼워 "장 마감 1초 전 주문",
     * "토큰 만료 직전 동시 갱신" 같은 시나리오를 재현할 수 있다.
     *
     * 저장·로깅은 UTC, 장 운영 판정만 Asia/Seoul 로 변환한다 (§09).
     */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    // JVM 기본 타임존을 UTC 로 고정한다 (§09).
    // 서버 타임존에 따라 동작이 달라지면 로컬에서 통과한 테스트가 운영에서 깨진다.
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    runApplication<StockStudyApplication>(*args)
}
