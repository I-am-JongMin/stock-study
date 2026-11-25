package com.stockstudy.support.secret

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * 하네스 V1 (일부) — 비밀값이 새는 경로를 카나리아로 일부러 공격한다 (ADR-022).
 *
 * 여기서는 Secret 타입 자체의 보장만 검증한다. 로그 마스킹과 Jackson fail-fast 는
 * 해당 의존성이 있는 api-gateway 쪽 테스트에 있다 — core-support 는 순수해야 하므로
 * Jackson 을 테스트 의존성으로도 들이지 않는다.
 */
class SecretTest : FunSpec({

    val canary = "CANARY-sk-live-9f8e7d6c5b4a"

    test("toString 은 평문을 노출하지 않는다") {
        Secret(canary).toString() shouldBe Secret.MASK
    }

    test("문자열 보간은 평문을 노출하지 않는다 — data class 였다면 뚫리는 경로") {
        val secret = Secret(canary)
        "설정 로딩: appKey=$secret" shouldNotContain canary
        "설정 로딩: appKey=$secret" shouldContain Secret.MASK
    }

    test("컬렉션에 담겨 출력돼도 평문을 노출하지 않는다") {
        val config = mapOf("appKey" to Secret(canary), "accountNo" to Secret("67528985"))
        withClue("Map.toString 은 원소의 toString 을 호출한다") {
            config.toString() shouldNotContain canary
            config.toString() shouldNotContain "67528985"
        }
    }

    test("예외 메시지에 실수로 넣어도 평문은 toString 을 거쳐 마스킹된다") {
        val secret = Secret(canary)
        val e = IllegalStateException("인증 실패: $secret")
        e.message shouldNotContain canary
    }

    test("reveal 은 평문을 돌려준다 — 유일하게 허용된 탈출구") {
        Secret(canary).reveal() shouldBe canary
    }

    test("같은 값이면 동등하다 — 캐시 키·중복 제거에 쓸 수 있어야 한다") {
        Secret(canary) shouldBe Secret(canary)
        Secret(canary).hashCode() shouldBe Secret(canary).hashCode()
        Secret(canary) shouldNotBe Secret("다른값")
    }

    test("blank 는 값을 노출하지 않고 설정 누락을 감지한다") {
        Secret("").blank() shouldBe true
        Secret(canary).blank() shouldBe false
    }
})
