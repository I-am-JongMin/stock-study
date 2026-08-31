package com.stockstudy.domain.money

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.math.BigDecimal

/**
 * 이 테스트는 Spring 컨텍스트 없이 밀리초 단위로 돈다.
 * §04 의 core-domain 순수성 규칙이 실제로 사주는 이득이 이것이다.
 */
class MoneyTest : FunSpec({

    test("같은 통화끼리 더하고 뺀다") {
        (Money.krw(70_000) + Money.krw(500)) shouldBe Money.krw(70_500)
        (Money.krw(70_000) - Money.krw(500)) shouldBe Money.krw(69_500)
    }

    test("단가 × 수량") {
        (Money.krw(70_000) * 3L) shouldBe Money.krw(210_000)
    }

    test("통화가 다르면 연산을 거부한다") {
        val won = Money.krw(1_000)
        val dollar = Money.of(BigDecimal("1.00"), Currency.USD)

        shouldThrow<IllegalArgumentException> { won + dollar }
        shouldThrow<IllegalArgumentException> { won - dollar }
        shouldThrow<IllegalArgumentException> { won < dollar }
    }

    test("오버플로는 조용히 감싸지 않고 예외로 실패한다") {
        // 잔고 계산이 음수로 감싸버리면 INV-1 이 애플리케이션 레벨에서 무의미해진다.
        shouldThrow<ArithmeticException> { Money.krw(Long.MAX_VALUE) + Money.krw(1) }
        shouldThrow<ArithmeticException> { Money.krw(Long.MAX_VALUE) * 2L }
    }

    test("KRW 는 원 미만 자리를 조용히 버리지 않고 예외로 실패한다") {
        // 0.5원을 0원이나 1원으로 만들면 원장 합계(INV-2)가 어긋난다.
        shouldThrow<ArithmeticException> { Money.of(BigDecimal("100.5"), Currency.KRW) }
        Money.of(BigDecimal("100"), Currency.KRW) shouldBe Money.krw(100)
    }

    test("USD 는 센트까지 허용하고 그 아래는 거부한다") {
        Money.of(BigDecimal("12.34"), Currency.USD).minorUnits shouldBe 1_234L
        shouldThrow<ArithmeticException> { Money.of(BigDecimal("12.345"), Currency.USD) }
    }

    test("표시 형식") {
        Money.krw(70_000).toString() shouldBe "₩70000"
        Money.of(BigDecimal("12.34"), Currency.USD).toString() shouldBe "\$12.34"
    }

    // ── 속성 기반 테스트 (§12) ──
    // 예시 몇 개가 아니라 무작위 입력 수천 건에 대해 성질이 성립하는지 본다.
    // 체결 로직과 불변식 검증에서 본격적으로 쓸 도구를 여기서 먼저 세팅한다.

    test("성질: BigDecimal 왕복 변환은 값을 보존한다") {
        checkAll(Arb.long(-1_000_000_000_000L..1_000_000_000_000L)) { won ->
            Money.of(Money.krw(won).toBigDecimal(), Currency.KRW) shouldBe Money.krw(won)
        }
    }

    test("성질: 더한 뒤 다시 빼면 원래 금액이다") {
        val bound = 1_000_000_000_000L
        checkAll(Arb.long(-bound..bound), Arb.long(-bound..bound)) { a, b ->
            (Money.krw(a) + Money.krw(b) - Money.krw(b)) shouldBe Money.krw(a)
        }
    }

    test("성질: 부호 판정은 서로 배타적이다") {
        checkAll(Arb.long()) { won ->
            val money = Money.krw(won)
            listOf(money.isNegative, money.isZero, money.isPositive).count { it } shouldBe 1
        }
    }
})
