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
    implementation(libs.spring.boot.starter.data.redis)
    // 종목마스터 동기화 (§F1.1) — JPA 가 아니라 JdbcClient 로 SQL 을 그대로 본다 (ADR-004)
    implementation(libs.spring.boot.starter.jdbc)
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
    testRuntimeOnly(libs.junit.platform.launcher)

    // 하네스 V5 — 분산락의 상호 배제는 진짜 Redis 에서만 증명된다 (ADR-024)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    // 하네스 V4 — 녹화한 키움 응답을 오프라인에서 재생한다
    testImplementation(libs.wiremock.standalone)
}

/**
 * 하네스 V7 — 키움 모의서버 라이브 스모크 (ADR-023, docs/harness.md).
 *
 * `check` 밖에 두는 이유 — 이 테스트의 실패는 우리 버그가 아니라 **계약 변경 신호**다.
 * 외부 가용성에 빌드가 인질 잡히는 순간 사람들은 실패를 무시하기 시작하고,
 * 그때부터 하네스는 장식이 된다.
 *
 * `.env` 를 읽어 환경변수로 넘긴다 — 사람이 export 를 기억하게 하지 않는다.
 * 키가 없으면 테스트가 스스로 skip 하므로 이 태스크는 어떤 환경에서도 실패하지 않는다.
 */
val liveTest by tasks.registering(Test::class) {
    group = "verification"
    description = "키움 모의서버에 실제로 붙어 계약이 그대로인지 본다 (하네스 V7)"

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath

    useJUnitPlatform { includeTags("live") }
    // 외부 상태를 보는 것이 목적이다. 캐시된 성공을 재사용하면 의미가 없다.
    outputs.upToDateWhen { false }

    val envFile = rootProject.layout.projectDirectory.file(".env").asFile
    doFirst {
        if (!envFile.exists()) {
            logger.lifecycle(".env 가 없습니다 — 라이브 스모크는 건너뜁니다")
            return@doFirst
        }
        envFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
            .forEach { line ->
                val name = line.substringBefore("=").trim()
                val value = line.substringAfter("=").trim()
                if (value.isNotEmpty()) environment(name, value)
            }
    }
}

// 라이브 태그는 평소 테스트에서 제외한다. check 는 오프라인에서 항상 통과해야 한다.
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("live") }
}
