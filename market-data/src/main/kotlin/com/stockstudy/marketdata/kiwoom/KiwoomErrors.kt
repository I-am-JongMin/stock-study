package com.stockstudy.marketdata.kiwoom

/**
 * 키움 호출 실패.
 *
 * **HTTP 상태코드는 믿을 수 없다.** 실측에서 인증 실패도, 지원하지 않는 API ID 도 전부
 * `HTTP 200` 으로 돌아왔다 (2026-11-26 모의 서버). 진짜 상태는 본문의 `return_code` 다.
 * 그래서 이 예외는 HTTP 계층이 아니라 응답 봉투를 해석한 결과로만 만들어진다.
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

    OTHER;

    companion object {
        fun of(returnCode: Int): KiwoomErrorKind = when (returnCode) {
            0 -> OK
            1 -> BAD_REQUEST
            3 -> AUTH_FAILED
            else -> OTHER
        }
    }
}

/** 남이 갱신 중인데 대기 데드라인까지 쓸 수 있는 토큰을 얻지 못했다 (§R3). */
class KiwoomTokenUnavailableException(message: String) : RuntimeException(message)
