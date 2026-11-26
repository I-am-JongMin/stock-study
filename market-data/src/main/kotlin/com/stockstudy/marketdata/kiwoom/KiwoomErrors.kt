package com.stockstudy.marketdata.kiwoom

/**
 * 키움 호출 실패.
 *
 * **HTTP 상태코드는 믿을 수 없다.** 실측에서 인증 실패도, 지원하지 않는 API ID 도 전부
 * `HTTP 200` 으로 돌아왔다 (2026-11-26 모의 서버). 진짜 상태는 본문의 `return_code` 다.
 *
 * 다만 **유량 초과만은 429** 로 온다 — 즉 "상태코드는 항상 200"도 사실이 아니다.
 * 그래서 두 경로를 모두 봉투 해석으로 모은다: 2xx 면 본문의 `return_code` 로, 비-2xx 면
 * 오류 응답 본문을 같은 방식으로 읽어 이 예외를 만든다. 호출부가 보는 것은 언제나
 * [KiwoomErrorKind] 하나다.
 */
class KiwoomApiException(
    val returnCode: Int,
    val returnMsg: String?,
    val kind: KiwoomErrorKind,
    operation: String,
) : RuntimeException("키움 $operation 실패 [return_code=$returnCode, kind=$kind]: $returnMsg")

/**
 * `return_code` 분류.
 *
 * 세부 코드는 메시지 안에 대괄호로 들어온다 — 예: `인증에 실패했습니다[8005:Token이
 * 유효하지 않습니다]`. 분류를 여기서 한 번만 하고, 호출부는 [KiwoomErrorKind] 만 본다.
 *
 * 이 매핑은 **관측에서 나왔지 문서에서 나오지 않았다.** 키움이 바꾸면 라이브 스모크(V7)가
 * 먼저 알려준다.
 */
enum class KiwoomErrorKind {
    /** `return_code = 0`. 다만 "성공"이 "데이터가 있다"는 뜻은 아니다 — 없는 종목도 0 이다. */
    OK,

    /** `return_code = 3`. 토큰 무효·만료(8005), 실전/모의 키 불일치(8030). 재발급하면 풀릴 수 있다. */
    AUTH_FAILED,

    /** `return_code = 1`. 지원하지 않는 API ID(1504) 등 — 재시도해도 소용없다. */
    BAD_REQUEST,

    /**
     * `return_code = 5`. 유량 초과(1700). **이때만은 HTTP 상태코드도 의미가 있다 — 429 다.**
     *
     * 라이브 스모크(V7)가 찾아냈다. 토큰 발급(`au10001`) 자체에도 유량이 걸려 있어서,
     * 짧은 시간에 발급을 반복하면 여기로 떨어진다 — §R3 의 전제가 실측으로 확인된 셈이다.
     * 분산락은 *동시* 발급을 막지만 *빈도*를 막지는 않는다. 토큰을 버리고 다시 받는 경로
     * (`invalidate()` 후 재시도)를 1 회로 묶어둔 이유가 이것이다.
     */
    RATE_LIMITED,

    OTHER;

    companion object {
        fun of(returnCode: Int): KiwoomErrorKind = when (returnCode) {
            0 -> OK
            1 -> BAD_REQUEST
            3 -> AUTH_FAILED
            5 -> RATE_LIMITED
            else -> OTHER
        }
    }
}

/** 남이 갱신 중인데 대기 데드라인까지 쓸 수 있는 토큰을 얻지 못했다 (§R3). */
class KiwoomTokenUnavailableException(message: String) : RuntimeException(message)
