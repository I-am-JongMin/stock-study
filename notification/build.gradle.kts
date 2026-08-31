//
// notification — 알림 Outbox·발송 채널 어댑터 (§F6, §R4, §08)
//
// spring-boot-starter-web 이 여기 없는 것에 주의:
// 이 모듈은 HTTP 서버가 아니라 HTTP 클라이언트(텔레그램 발송)가 필요하다.
// starter-web 을 붙이면 이 모듈이 서버를 띄우려 하므로 RestClient 만 쓴다.
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
    implementation(libs.spring.boot.starter.jdbc) // Outbox 테이블 폴링
    implementation(libs.spring.boot.starter.aop)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)

    // R4 — 발송 실패 시 재시도·백오프
    implementation(libs.resilience4j.spring.boot3)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.mockk)
}
