# stockStudy

실시간 주식 트레이딩 플랫폼. **거래 시스템의 어려운 문제들을 우회하지 않고 직접 해결하면서
배우는 것**이 목적이다. 산출물은 코드이지만 목표는 지식이다.

우회하지 않을 문제 네 가지:

1. **실시간성** — 초당 수천 건의 시세를 화면까지 500ms 안에
2. **동시성** — 같은 계좌에 요청이 쏟아져도 돈이 새지 않게
3. **장애** — 외부 시세 API가 죽어도 서비스가 무너지지 않게
4. **알림** — 유실 없이, 중복 없이, 폭발하지 않게

Kotlin 2.1 · Spring Boot 3.5 · PostgreSQL 16 · Redis 7 · Java 21

---

## 빠른 시작

```bash
docker compose up -d                    # PostgreSQL + Redis
./gradlew :api-gateway:bootRun          # 앱 실행 (Flyway가 스키마 적용)
curl -s localhost:8080/health | jq      # UP 확인
```

```bash
./gradlew :core-domain:check   # 도메인 테스트 + 순수성 검사 (Docker 불필요)
./gradlew check                # 전체 (Testcontainers → Docker 데몬 필요)
```

### 비밀정보 보호

이 저장소는 공개다. **한 번 푸시된 비밀은 되돌릴 수 없다** — 커밋을 지워도 GitHub 는
orphan commit 을 한동안 보관하고, 공개 저장소는 봇이 수 초 안에 긁어간다.

그래서 두 겹으로 막는다.

| 막는 것 | 수단 |
| --- | --- |
| 실수로 `.env` 를 add | `.gitignore` |
| `git add -f .env`, `application.yml` 에 키 직접 박기 | [`.githooks/pre-commit`](.githooks/pre-commit) |

훅은 **스테이징된 추가 라인**만 검사한다. 환경변수 참조(`${KIS_APP_KEY}`)와 빈 값은
통과하고, 값이 채워진 키 할당·KIS/GitHub/Slack/AWS 토큰 형식·개인키 블록을 차단한다.

`.git/hooks` 는 커밋되지 않아 클론하면 사라지므로 `core.hooksPath` 를 저장소 안으로
돌려 둔다. 그 설정은 클론마다 한 번 필요한데, 사람이 기억하게 하지 않고 빌드가
대신 한다 — 테스트를 한 번이라도 돌리면 걸린다. 수동으로 걸려면:

```bash
git config core.hooksPath .githooks
```

오탐이면 해당 줄 끝에 `pragma: allowlist secret` 주석을 붙인다.

---

## 문서

| 문서 | 내용 |
| --- | --- |
| [`docs/next-steps.md`](docs/next-steps.md) | **작업을 이어갈 때 가장 먼저 읽는다** — 현재 상태와 다음 할 일 |
| [`docs/spec.md`](docs/spec.md) | 기술 스펙 — 요구사항, 불변식, 동시성 6케이스, 장애 7개, 로드맵 |
| [`docs/decisions.md`](docs/decisions.md) | ADR 16개 — 각 결정의 근거와 배제한 대안, 재고 시점 |
| [`docs/tech-stack.md`](docs/tech-stack.md) | 스택·버전·모듈별 의존성 |

---

## 모듈 구조

의존 방향은 항상 아래에서 위로만 흐른다.

```
core-support     순수 Kotlin — 에러 코드, ID 생성기, Clock 헬퍼
     ↑
core-domain      순수 Kotlin — 주문 상태 머신, 체결 규칙, Money
     ↑           ⚠️ 프레임워크 의존 금지. 빌드가 강제한다
     ├── market-data     시세 수집·집계·캐시
     ├── trading         계좌·주문·체결 엔진·원장
     └── notification    알림 Outbox·발송 채널
              ↑
        api-gateway      REST + SSE — 유일한 실행 모듈
```

`core-domain`이 프레임워크를 모르는 것은 미학이 아니라 실용이다. 체결 로직이 순수 함수면 Spring
컨텍스트 없이 초당 수만 번 실행할 수 있고, 그래야 무작위 주문 수만 건으로 불변식을 검증하는
속성 기반 테스트가 가능하다. 이 규칙은 `checkDomainPurity` Gradle 태스크가 강제한다 — 누군가
Spring 의존성을 추가하면 리뷰가 아니라 빌드가 막는다.

---

## 이 프로젝트의 척추 — 불변식

깨지면 돈이 새는 조건 7개. **애플리케이션 로직과 DB 제약에서 이중으로 방어한다.**
애플리케이션에 버그가 있어도 DB가 마지막 방어선이 되어야 한다.

| ID | 불변식 | 방어 |
| --- | --- | --- |
| INV-1 | `available_cash >= 0` | DB `CHECK` + 조건부 UPDATE |
| INV-2 | 잔고 컬럼 = 원장 합계 | 정합성 뷰 + 알람 |
| INV-3 | `position.quantity >= 0` | DB `CHECK` + 조건부 UPDATE |
| INV-4 | 금액 변동 ↔ 원장 1행 | 같은 트랜잭션 + append-only 트리거 |
| INV-5 | `filled_quantity <= quantity` | DB `CHECK` |
| INV-6 | 같은 멱등키로 주문 2건 불가 | DB `UNIQUE` |
| INV-7 | 상태 전이는 상태 머신 경로만 | 조건부 UPDATE + affected rows |

제약 위반 예외가 발생하면 그것은 장애가 아니라 **버그 리포트**다. DB가 막아준 것은 데이터가
안전하다는 뜻이고, 그 스택트레이스가 동시성 버그의 위치를 알려준다.

---

## 로드맵

| Phase | 목표 | 상태 |
| --- | --- | --- |
| 0 | 골격 — 멀티모듈, Docker Compose, 베이스라인 스키마 | ✅ |
| 1 | 종목 + 일봉 | |
| 2 | 실시간 시세 — WS → Redis → SSE | |
| 3 | **계좌·주문·체결** — 이 프로젝트의 정점 | |
| 4 | 알림 — Outbox 패턴 | |
| 5 | 장애 내성 — 카오스 테스트 | |
| 6 | 관측성 | |
| 7 | 미국 주식 확장 — 설계의 채점표 | |

Phase 3에 시간을 가장 많이 쓰는 것이 맞다. 1·2는 그 준비이고, 4~6은 그것을 실서비스 수준으로
끌어올리는 과정이다.
