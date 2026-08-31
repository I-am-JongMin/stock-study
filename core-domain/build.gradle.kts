//
// core-domain — 순수 Kotlin. Spring 도 JPA 도 모른다 (§04).
//
// 이 규칙이 미학이 아니라 실용인 이유:
// 체결 로직이 순수 함수면 DB 도 Spring 컨텍스트도 없이 초당 수만 번 실행할 수 있다.
// 그래야 §12 의 속성 기반 테스트로 불변식을 실제로 검증할 수 있다.
//
// 규칙은 아래 checkDomainPurity 태스크가 빌드 단계에서 강제한다. 규율에 맡기지 않는다.
//
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core-support"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.property)
}

/**
 * core-domain 의 런타임 classpath 에 프레임워크가 새어 들어오면 빌드를 실패시킨다.
 *
 * 누군가(혹은 미래의 나) 편의를 위해 Spring 의존성을 하나 추가하는 순간
 * 도메인 테스트가 무거워지고 §12 의 속성 기반 테스트가 불가능해진다.
 * 그 시점을 사람이 리뷰로 잡아내길 기대하지 않고 빌드가 잡는다.
 */
val checkDomainPurity by tasks.registering {
    group = "verification"
    description = "core-domain 이 프레임워크에 의존하지 않는지 검증한다 (§04)"

    val forbidden = listOf(
        "org.springframework",
        "jakarta.persistence",
        "com.fasterxml.jackson",
        "org.hibernate",
        "io.micrometer",
    )
    val components = configurations.named("runtimeClasspath").map { conf ->
        conf.incoming.resolutionResult.allComponents.map { it.id.displayName }
    }

    doLast {
        val offenders = components.get().filter { id ->
            forbidden.any { id.startsWith(it) || id.contains(":$it") }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("core-domain 순수성 위반 (§04) — 프레임워크 의존성이 발견되었습니다:")
                    offenders.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine("도메인 로직은 프레임워크 없이 실행 가능해야 합니다.")
                    appendLine("해당 의존성이 필요하다면 trading / market-data 등 상위 모듈로 옮기세요.")
                }
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkDomainPurity)
}
