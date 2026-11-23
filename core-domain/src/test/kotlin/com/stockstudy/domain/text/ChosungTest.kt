package com.stockstudy.domain.text

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll

/**
 * 초성 추출은 유니코드 산술이라 입력 공간이 유한하고 정확히 11,172 개다.
 * 표본을 뽑을 이유가 없으므로 **완성형 음절 전부**를 돌린다 (§12).
 */
class ChosungTest : FunSpec({

    test("스펙 §F1.2 의 예시 — ㅅㅅㅈㅈ 로 삼성전자를 찾는다") {
        Chosung.of("삼성전자") shouldBe "ㅅㅅㅈㅈ"
    }

    test("실제 종목명") {
        Chosung.of("카카오") shouldBe "ㅋㅋㅇ"
        Chosung.of("현대차") shouldBe "ㅎㄷㅊ"
        Chosung.of("셀트리온") shouldBe "ㅅㅌㄹㅇ"
        Chosung.of("포스코홀딩스") shouldBe "ㅍㅅㅋㅎㄷㅅ"
    }

    test("종성이 있든 없든 초성은 같다") {
        // 음절 코드가 초성 × 588 단위로 묶여 있다는 것의 직접적인 귀결이다.
        listOf('가', '각', '갛', '괅').forEach { Chosung.choseongOf(it) shouldBe 'ㄱ' }
    }

    test("음절 블록의 양 끝") {
        Chosung.choseongOf('가') shouldBe 'ㄱ'   // U+AC00, 첫 음절
        Chosung.choseongOf('힣') shouldBe 'ㅎ'   // U+D7A3, 마지막 음절
    }

    test("완성형 음절이 아니면 거부한다") {
        // 조용히 물음표 같은 걸 돌려주면 색인이 망가진 걸 아무도 모른다.
        shouldThrow<IllegalArgumentException> { Chosung.choseongOf('A') }
        shouldThrow<IllegalArgumentException> { Chosung.choseongOf('ㄱ') }   // 자모는 음절이 아니다
        shouldThrow<IllegalArgumentException> { Chosung.choseongOf(' ') }
    }

    test("거부 메시지에 코드포인트를 넣어 디버깅 가능하게 한다") {
        val message = shouldThrow<IllegalArgumentException> { Chosung.choseongOf('A') }.message!!
        message shouldContain "U+41"
    }

    // ────────────────────────── 혼합 입력 ──────────────────────────

    test("라틴 문자는 대문자로 통일한다") {
        // lg전자 로 검색해도 LG전자 가 걸려야 한다.
        Chosung.of("LG전자") shouldBe "LGㅈㅈ"
        Chosung.of("lg전자") shouldBe "LGㅈㅈ"
        Chosung.of("SK하이닉스") shouldBe "SKㅎㅇㄴㅅ"
    }

    test("숫자는 남기고 공백과 기호는 버린다") {
        Chosung.of("KODEX 200") shouldBe "KODEX200"
        Chosung.of("TIGER 미국S&P500") shouldBe "TIGERㅁㄱSP500"
        Chosung.of("  삼성 전자  ") shouldBe "ㅅㅅㅈㅈ"
    }

    test("이미 초성으로 입력된 검색어는 그대로 통과한다") {
        // 저장값과 검색어가 같은 함수를 지나야 하므로 이 경로가 반드시 필요하다.
        Chosung.of("ㅅㅅㅈㅈ") shouldBe "ㅅㅅㅈㅈ"
        Chosung.of("ㅋㅋ") shouldBe "ㅋㅋ"
    }

    test("빈 문자열은 빈 문자열") {
        Chosung.of("") shouldBe ""
        Chosung.of("   ") shouldBe ""
    }

    // ────────────────────────── 속성 기반 ──────────────────────────

    test("완성형 음절 11172 개 전부가 19 개 초성 중 하나로 떨어진다") {
        val choseong = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ".toSet()
        var count = 0

        for (code in '가'.code..'힣'.code) {
            val syllable = code.toChar()
            val result = Chosung.of(syllable.toString())

            check(result.length == 1) { "'$syllable' 의 초성이 1 글자가 아니다: '$result'" }
            check(result[0] in choseong) { "'$syllable' → '$result' 는 초성이 아니다" }
            count++
        }

        count shouldBe 11_172
    }

    test("멱등이다 — 저장값과 검색어가 같은 함수를 두 번 지나도 안전하다") {
        checkAll<String>(1_000) { raw ->
            val once = Chosung.of(raw)
            Chosung.of(once) shouldBe once
        }
    }

    test("한글만 들어오면 결과 길이는 음절 수와 같다") {
        val hangulText = Arb.list(hangulSyllable, 0..20).map { it.joinToString("") }

        checkAll(500, hangulText) { hangul ->
            Chosung.of(hangul).length shouldBe hangul.length
        }
    }
})

/** 완성형 음절 한 글자를 뽑는 생성기. */
private val hangulSyllable: Arb<Char> = arbitrary { rs ->
    ('가'.code + rs.random.nextInt(11_172)).toChar()
}
