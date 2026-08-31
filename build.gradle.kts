//
// 루트 빌드 — 플러그인은 여기서 선언만 하고(apply false) 각 모듈이 필요한 것만 적용한다.
// core-domain 이 Spring 플러그인을 받지 않는 것이 §04 의 핵심 규칙이므로,
// 루트에서 일괄 적용(allprojects)하지 않는다.
//
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dep.management) apply false
}

group = "com.stockstudy"
version = "0.1.0-SNAPSHOT"

// 전체 모듈에 공통으로 필요한 최소한만 설정한다.
subprojects {
    repositories {
        mavenCentral()
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // 저장·로깅은 UTC (§09). 테스트도 같은 조건에서 돌려야 재현된다.
        systemProperty("user.timezone", "UTC")
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
