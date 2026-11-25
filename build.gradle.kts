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

/**
 * 하네스 G3 — 추적되는 전체 파일에서 비밀정보 패턴을 스캔한다 (ADR-022).
 *
 * pre-commit 훅(G2)과 같은 패턴을 보지만 역할이 다르다. 훅은 커밋되는 순간의
 * 추가 라인만 보고, `--no-verify` 로 우회할 수 있으며, 훅이 설치되기 전에 들어간
 * 파일은 영영 보지 못한다. 이 태스크는 저장소의 현재 상태 전체를 보고 check 에서
 * 실패시킨다 — 어느 경로로 들어왔든 "지금 존재한다"는 사실 자체를 잡는다.
 */
val guardSecrets by tasks.registering {
    group = "verification"
    description = "추적 파일 전체에서 비밀정보 패턴을 스캔한다 (하네스 G3)"

    val rootDir = layout.projectDirectory.asFile

    doLast {
        val tracked = ProcessBuilder("git", "ls-files", "-z")
            .directory(rootDir).start()
            .inputStream.bufferedReader().readText()
            .split('\u0000').filter { it.isNotBlank() }

        // 훅 자신과 템플릿은 패턴 문자열을 당연히 포함하므로 제외한다 (훅과 동일한 규칙).
        val excluded = listOf(".githooks/", ".env.example", "build.gradle.kts")

        val patterns = listOf(
            // 값이 실제로 채워진 비밀 환경변수 할당 (${...} 보간과 빈 값은 통과)
            Regex("""(KIS|KIWOOM)_(APP_KEY|APP_SECRET|ACCOUNT_NO)\s*=\s*[^\s$]"""),
            Regex("""TELEGRAM_BOT_TOKEN\s*=\s*[^\s$]"""),
            // yml/properties 에 직접 박힌 값 — 16자 이상이면 placeholder 가 아니다
            Regex("""(?i)(app-?key|app-?secret|secret-?key|bot-?token)\s*:\s*["']?[A-Za-z0-9/+=_-]{16,}"""),
            // 발급자가 정해진 토큰 형식 — 형태만으로 확실하다
            Regex("""gh[pousr]_[A-Za-z0-9]{30,}"""),
            Regex("""AKIA[0-9A-Z]{16}"""),
            Regex("""xox[baprs]-[A-Za-z0-9-]{10,}"""),
            Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
        )

        val offenders = buildList {
            tracked.filter { path -> excluded.none { path.startsWith(it) || path == it } }
                .map { path -> path to File(rootDir, path) }
                .filter { (_, f) -> f.isFile && f.length() < 1_000_000 }
                .forEach { (path, f) ->
                    f.readLines().forEachIndexed { i, line ->
                        if (!line.contains("pragma: allowlist secret") &&
                            patterns.any { it.containsMatchIn(line) }
                        ) add("  $path:${i + 1}: ${line.trim().take(100)}")
                    }
                }
        }

        if (offenders.isNotEmpty()) {
            throw GradleException(
                "비밀정보로 보이는 내용이 추적 파일에 있습니다 (ADR-022):\n" +
                    offenders.joinToString("\n") +
                    "\n\n오탐이면 해당 줄 끝에 주석을 붙이세요: pragma: allowlist secret",
            )
        }
    }
}

/**
 * 하네스 G4 — 실전 도메인이 소스에 하드코딩되면 실패한다 (ADR-020/021).
 *
 * 실전 전환은 환경변수 교체만으로 이뤄져야 한다. 코드나 설정 리소스에
 * api.kiwoom.com 이 박히는 순간 그 보장이 깨진다. mockapi.kiwoom.com 은 허용한다.
 */
val guardRealTradingDomain by tasks.registering {
    group = "verification"
    description = "실전 도메인(api.kiwoom.com) 하드코딩을 차단한다 (하네스 G4)"

    val rootDir = layout.projectDirectory.asFile

    doLast {
        // 음수 lookbehind — mockapi.kiwoom.com 의 부분 문자열로는 걸리지 않는다
        val realDomain = Regex("""(?<![A-Za-z])api\.kiwoom\.com""")

        val offenders = ProcessBuilder("git", "ls-files", "-z")
            .directory(rootDir).start()
            .inputStream.bufferedReader().readText()
            .split('\u0000')
            .filter { it.contains("/src/") }
            .flatMap { path ->
                val f = File(rootDir, path)
                if (!f.isFile) return@flatMap emptyList()
                f.readLines().mapIndexedNotNull { i, line ->
                    if (realDomain.containsMatchIn(line)) "  $path:${i + 1}: ${line.trim().take(100)}"
                    else null
                }
            }

        if (offenders.isNotEmpty()) {
            throw GradleException(
                "실전 도메인이 소스에 하드코딩되어 있습니다 (ADR-020/021 위반):\n" +
                    offenders.joinToString("\n") +
                    "\n\n실전 전환은 KIWOOM_BASE_URL 환경변수로만 합니다.",
            )
        }
    }
}

// 전체 모듈에 공통으로 필요한 최소한만 설정한다.
subprojects {
    repositories {
        mavenCentral()
    }

    // 하네스 G3·G4 — 어느 모듈의 check 를 돌려도 저장소 가드를 지난다 (docs/harness.md)
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(
            rootProject.tasks.named("guardSecrets"),
            rootProject.tasks.named("guardRealTradingDomain"),
        )
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
