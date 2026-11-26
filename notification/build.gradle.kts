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
