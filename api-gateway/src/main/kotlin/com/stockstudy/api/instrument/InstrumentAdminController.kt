package com.stockstudy.api.instrument

import com.stockstudy.marketdata.instrument.InstrumentSyncService
import com.stockstudy.marketdata.instrument.MarketSyncResult
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 종목마스터 동기화 수동 트리거.
 *
 * **아직 인증이 없다 (Q4 미결).** 스케줄러를 붙이기 전에 사람이 돌려보고 결과를 눈으로
 * 확인할 경로가 필요해서 먼저 열어둔다. Phase 3 에서 인증을 정할 때 관리자 경로 전체를
 * 그 뒤로 옮긴다 — 그때까지 이 애플리케이션은 로컬에서만 띄운다.
 *
 * 스케줄러를 아직 붙이지 않은 이유도 결정이 남아서다: 장중에 돌리면 레이트 리밋을
 * 시세 조회와 나눠 쓰게 되고, 상장폐지 판정은 장 마감 후가 정확하다 (ADR-026).
 */
@RestController
@RequestMapping("/admin/instruments")
class InstrumentAdminController(
    private val instrumentSync: InstrumentSyncService,
) {

    @PostMapping("/sync")
    fun sync(): List<MarketSyncResult> = instrumentSync.syncAll()
}
