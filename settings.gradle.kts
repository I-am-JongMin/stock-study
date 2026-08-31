rootProject.name = "stockStudy"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// §04 모듈 구조. 의존 방향은 항상 아래에서 위로만 흐른다.
//   core-support  ← core-domain  ← market-data / trading / notification  ← api-gateway
include(
    "core-support",
    "core-domain",
    "market-data",
    "trading",
    "notification",
    "api-gateway",
)
