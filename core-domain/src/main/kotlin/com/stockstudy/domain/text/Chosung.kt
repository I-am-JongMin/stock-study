package com.stockstudy.domain.text

/**
 * 한글 초성 추출 (§F1.2 — `ㅅㅅㅈㅈ` → 삼성전자).
 *
 * **왜 DB 함수가 아니라 순수 함수인가**
 * 초성 규칙은 유니코드 산술이지 데이터가 아니다. core-domain 에 두면 Spring 도 DB 도 없이
 * 음절 11,172 개 전부를 속성 기반 테스트로 밀어넣을 수 있다 (§12). PL/pgSQL 로 쓰면
 * 같은 검증을 하려고 컨테이너를 띄워야 하고, 규칙이 바뀔 때 마이그레이션이 필요해진다.
 *
 * **왜 저장과 질의가 같은 함수를 지나야 하는가**
 * `instrument.name_chosung` 에 넣는 값과 사용자가 입력한 검색어를 **둘 다** [of] 에
 * 통과시킨다. 한쪽만 정규화하면 `lg전자` 같은 입력이 조용히 안 맞는다.
 * 그래서 [of] 는 멱등이어야 한다 — `of(of(x)) == of(x)`. 테스트로 고정해 둔다.
 *
 * **변환 규칙**
 * | 입력 | 결과 | 이유 |
 * | --- | --- | --- |
 * | 완성형 음절 `가`~`힣` | 초성 1글자 | 본래 목적 |
 * | 이미 호환 자모 `ㅅ` | 그대로 | 사용자가 초성을 직접 입력한 경우 |
 * | 라틴 문자 | 대문자 | `lg전자` 와 `LG전자` 가 같아야 한다 |
 * | 숫자 | 그대로 | `KODEX 200` 의 `200` |
 * | 그 외(공백·기호) | 버림 | `TIGER 미국S&P500` 을 띄어쓰기·기호 없이 검색 |
 *
 * 매칭 방식(접두 일치냐 부분 일치냐)은 이 함수의 책임이 아니다. 조회 계층에서 정한다.
 */
object Chosung {

    /** 완성형 음절 블록. `가`(U+AC00) ~ `힣`(U+D7A3), 총 11,172 자. */
    private const val SYLLABLE_FIRST = '가'
    private const val SYLLABLE_LAST = '힣'

    /** 중성 21 × 종성 27+1 = 588. 음절은 초성마다 588 자씩 연속으로 배치되어 있다. */
    private const val SYLLABLES_PER_CHOSEONG = 21 * 28

    /**
     * 초성 19 자를 **호환 자모**(U+3131~)로 둔다.
     *
     * 음절을 분해하면 나오는 것은 한글 자모 영역(U+1100~)의 초성이지만, 키보드로 입력되고
     * 화면에 보이는 것은 호환 자모다. 두 영역의 `ㄱ`은 코드포인트가 달라서 눈으로는
     * 같아 보여도 문자열 비교가 실패한다. 사용자 입력과 맞춰야 하므로 호환 자모로 통일한다.
     */
    private const val CHOSEONG_TABLE = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"

    /** 호환 자모 블록 전체(자음·모음). 사용자가 이미 초성으로 입력한 경우 그대로 통과시킨다. */
    private const val COMPAT_JAMO_FIRST = 'ㄱ'
    private const val COMPAT_JAMO_LAST = 'ㆎ'

    /**
     * [text] 를 검색용 초성 문자열로 정규화한다.
     *
     * 저장할 때와 검색할 때 모두 이 함수를 쓴다. 멱등이다.
     */
    fun of(text: String): String = buildString(text.length) {
        for (ch in text) {
            when {
                ch in SYLLABLE_FIRST..SYLLABLE_LAST -> append(choseongOf(ch))
                ch in COMPAT_JAMO_FIRST..COMPAT_JAMO_LAST -> append(ch)
                ch.isLetter() -> append(ch.uppercaseChar())
                ch.isDigit() -> append(ch)
                // 공백·기호는 버린다. 종목명의 띄어쓰기는 자료 출처마다 달라서
                // 그대로 두면 같은 종목이 검색어에 따라 걸리다 말다 한다.
            }
        }
    }

    /**
     * 완성형 음절 하나의 초성을 돌려준다.
     *
     * 음절 코드는 `0xAC00 + (초성 × 588) + (중성 × 28) + 종성` 으로 **규칙적으로** 배열되어
     * 있다. 테이블 조회가 아니라 나눗셈 한 번이면 끝나는 이유다.
     *
     * @throws IllegalArgumentException 완성형 음절이 아닌 경우
     */
    fun choseongOf(syllable: Char): Char {
        require(syllable in SYLLABLE_FIRST..SYLLABLE_LAST) {
            "완성형 한글 음절이 아닙니다: '$syllable' (U+${syllable.code.toString(16).uppercase()})"
        }
        val index = (syllable - SYLLABLE_FIRST) / SYLLABLES_PER_CHOSEONG
        return CHOSEONG_TABLE[index]
    }

    /** 해당 문자가 초성으로 쓰일 수 있는 자음인지. 조회 계층의 입력 검증용. */
    fun isChoseong(ch: Char): Boolean = ch in CHOSEONG_TABLE
}
