//
// core-support — 프레임워크에 의존하지 않는 공통 유틸.
// 예정: 도메인 에러 코드(§10), ID 생성기, Clock 헬퍼(§01 함정 3).
// Phase 0 에서는 자리만 잡아둔다.
//
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions)
}
