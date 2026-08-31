package com.stockstudy.api.health

import org.springframework.boot.actuate.health.HealthComponent
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.boot.actuate.health.Status
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Phase 0 완료 조건: `GET /health` 가 200 을 반환한다.
 *
 * Actuator 는 이미 `/actuator/health` 를 제공하지만 굳이 컨트롤러를 두는 이유가 있다.
 * Actuator 의 base-path 를 `/` 로 바꾸면 `/metrics`, `/info` 같은 경로가 전부
 * 루트로 올라와 API 경로(§10)와 충돌할 여지가 생긴다. 헬스체크만 노출하고
 * 나머지 관리 엔드포인트는 `/actuator` 아래에 두는 것이 경계가 명확하다.
 *
 * 그리고 상태에 따라 HTTP 코드를 직접 정한다. DB 나 Redis 가 죽었을 때 200 을
 * 주면 로드밸런서가 죽은 인스턴스로 트래픽을 계속 보낸다 (§R5).
 */
@RestController
class HealthController(
    private val healthEndpoint: HealthEndpoint,
) {

    @GetMapping("/health")
    fun health(): ResponseEntity<HealthComponent> {
        val health = healthEndpoint.health()
        val httpStatus = when (health.status) {
            Status.UP -> HttpStatus.OK
            else -> HttpStatus.SERVICE_UNAVAILABLE
        }
        return ResponseEntity.status(httpStatus).body(health)
    }
}
