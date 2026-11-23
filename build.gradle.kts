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

/**
 * .githooks 를 git 의 훅 경로로 등록한다.
 *
 * 이 저장소는 공개라서 비밀정보가 한 번 푸시되면 되돌릴 수 없다.
 * .gitignore 는 실수로 add 하는 것만 막아주므로, application.yml 에 키를 직접
 * 박아넣는 경로는 pre-commit 훅이 막는다 (.githooks/pre-commit).
 *
 * .git/hooks 는 커밋되지 않아 클론하면 사라진다. core.hooksPath 를 저장소 안의
 * 디렉터리로 돌려두면 훅 자체가 버전 관리된다. 다만 그 설정은 클론마다 한 번
 * 필요하므로 빌드가 대신 해준다 — 사람이 기억하게 하지 않는다.
 */
val installGitHooks by tasks.registering {
    group = "build setup"
    description = "core.hooksPath 를 .githooks 로 설정한다"

    val gitDir = layout.projectDirectory.dir(".git").asFile
    val projectDir = layout.projectDirectory.asFile

    // 소스 아카이브로 받은 경우처럼 .git 이 없으면 조용히 건너뛴다.
    onlyIf { gitDir.exists() }

    doLast {
        fun git(vararg args: String): String =
            ProcessBuilder(listOf("git") + args)
                .directory(projectDir)
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader().readText().trim()

        val current = git("config", "--get", "core.hooksPath")
        if (current != ".githooks") {
            git("config", "core.hooksPath", ".githooks")
            logger.lifecycle("git 훅 경로를 .githooks 로 설정했습니다 (이전 값: '$current')")
        }
    }
}

// 전체 모듈에 공통으로 필요한 최소한만 설정한다.
subprojects {
    repositories {
        mavenCentral()
    }

    tasks.withType<Test>().configureEach {
        // 테스트를 한 번이라도 돌리면 훅이 걸린다.
        dependsOn(rootProject.tasks.named("installGitHooks"))
        useJUnitPlatform()
        // 저장·로깅은 UTC (§09). 테스트도 같은 조건에서 돌려야 재현된다.
        systemProperty("user.timezone", "UTC")
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
