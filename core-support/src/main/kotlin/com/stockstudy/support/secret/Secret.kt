package com.stockstudy.support.secret

/**
 * 로그·문자열 보간·직렬화로 값이 새어 나갈 수 없는 비밀값 컨테이너 (ADR-022).
 *
 * 설계가 이렇게 생긴 이유:
 *
 * - **data class 가 아니다.** data class 는 `toString()` 을 자동 생성해 값을 그대로
 *   노출하고, Kotlin 문자열 보간(`"$config"`)이 그것을 경고 없이 호출한다. 설정 객체를
 *   로그에 찍는 흔한 습관이 곧바로 유출 사고가 된다.
 *
 * - **public 프로퍼티가 없다.** Jackson 은 직렬화할 프로퍼티를 찾지 못해 기껏해야
 *   빈 객체(`{}`)를 내보낸다 — 어떤 경우에도 평문은 나가지 않는다.
 *   (Jackson 2.18 까지는 빈 빈(bean)이 예외였지만 2.19 부터 `{}` 가 기본값이다.
 *   버전 기본값에 기대지 않고 "평문 부재"만을 보장으로 삼는다 — 하네스 V1 이 검증.)
 *
 * - **평문은 [reveal] 로만 꺼낸다.** 이름이 일부러 무겁다 — 호출부가 코드리뷰에서
 *   눈에 띄어야 하고, `grep -rn "reveal()"` 한 번으로 평문이 나가는 모든 지점이
 *   열거되어야 한다. HTTP 전송 직전 등 꼭 필요한 곳에서만 호출한다.
 *
 * 하네스 V1 이 이 보장들을 상시 검증한다 (docs/harness.md §3).
 */
class Secret(value: String) {

    private val value: String = value

    /** 평문이 정말로 필요한 지점에서만 호출한다. 결과를 변수에 오래 들고 있지 않는다. */
    fun reveal(): String = value

    /**
     * 설정이 비어 있는지 확인하는 용도. 값 자체는 노출하지 않는다.
     *
     * `isBlank()` 가 아닌 이유 — `is` 접두 불리언 메서드는 Jackson 이 getter 로 인식해
     * `{"blank":false}` 로 직렬화해 버린다. 그러면 "프로퍼티가 없어 직렬화가 즉시
     * 실패한다"는 이 클래스의 fail-fast 보장이 깨진다. 하네스 V1 이 실제로 잡아낸 함정.
     */
    fun blank(): Boolean = value.isBlank()

    override fun toString(): String = MASK

    override fun equals(other: Any?): Boolean =
        this === other || (other is Secret && other.value == value)

    override fun hashCode(): Int = value.hashCode()

    companion object {
        const val MASK = "Secret(****)"
    }
}
