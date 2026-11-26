//
// trading — 계좌·주문·체결 엔진·원장 (§F3~F5). 이 프로젝트의 정점(Phase 3).
//
// JPA 가 없는 것은 누락이 아니라 결정이다 (ADR-004).
// C1·C2 의 핵심 기법은 손으로 쓴 조건부 UPDATE 와 affected rows 확인인데,
// JPA 의 엔티티 더티체킹과 자동 flush 는 그 패턴을 정면으로 방해한다.
// 여기서는 JdbcClient 로 SQL 을 그대로 본다.
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
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}") {
            // Spring Boot 3.5 BOM 은 kotlin.version 을 1.9.25 로 못박는다. 그런데
            // io.spring.dependency-management 는 BOM 을 *모든* configuration 에 적용해서
            // Kotlin 컴파일러 자신의 classpath(kotlinBuildToolsApiClasspath)까지 1.9.25 로
            // 끌어내린다 — 2.1.21 로 컴파일하라고 해놓고 컴파일러는 1.9.25 를 주는 꼴이라
            // NoClassDefFoundError 로 죽는다.
            //
            // api-gateway 가 멀쩡했던 이유는 spring-boot 플러그인이 이 속성을 KGP 버전으로
            // 덮어주기 때문이다. Boot 플러그인을 적용하지 않는 라이브러리 모듈은 그 보정을
            // 받지 못하므로 같은 일을 직접 한다. 버전 출처는 카탈로그 하나로 유지한다.
            bomProperty("kotlin.version", libs.versions.kotlin.get())
        }
    }
}

dependencies {
    implementation(project(":core-domain"))

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.aop)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)

    // C3 — 종목별 단일 처리 흐름(액터)
    implementation(libs.kotlinx.coroutines.core)

    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.mockk)
}
