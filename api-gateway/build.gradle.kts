//
// api-gateway — 유일한 실행 가능 모듈. REST + SSE, 인증, 요청 검증 (§10)
//
// Flyway 마이그레이션이 여기 있는 이유:
// Phase 0~6 은 단일 배포 단위이므로 스키마 소유자가 하나여야 한다.
// 여러 모듈이 각자 마이그레이션을 들고 있으면 적용 순서를 보장할 수 없다.
// 모듈을 실제로 분리하는 시점에 스키마도 함께 쪼갠다.
//
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.management)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":market-data"))
    implementation(project(":trading"))
    implementation(project(":notification"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)

    // 관측성 (§03) — /prometheus 로 메트릭 노출
    runtimeOnly(libs.micrometer.prometheus)
    // 구조화 로그 (§03) — traceId·accountId·orderId 를 JSON 필드로
    implementation(libs.logstash.logback.encoder)

    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotest.assertions)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
}
