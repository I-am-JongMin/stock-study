//
// market-data — 시세 수집(WebSocket 클라이언트)·집계·캐시 (§F2)
//
// 코루틴이 여기에 있는 이유: C3(종목별 직렬화)와 C5(백프레셔)를
// Channel 기반 액터로 구현한다. 종목 하나 = Channel 하나.
//
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.dep.management)
}

kotlin {
    jvmToolchain(21)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
    }
}

dependencies {
    implementation(project(":core-domain"))

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.websocket)
    implementation(libs.spring.boot.starter.aop)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)

    // C5 — 백프레셔가 있는 Channel
    implementation(libs.kotlinx.coroutines.core)

    // R1·R2·R3 — 서킷 브레이커, 재시도, 백오프
    implementation(libs.resilience4j.spring.boot3)
    implementation(libs.resilience4j.kotlin)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.mockk)
}
