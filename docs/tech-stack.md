# 기술 스택

> 결정의 **근거**는 [`decisions.md`](./decisions.md) (ADR)에 있다. 이 문서는 **무엇을 쓰는지**와
> 버전을 정리한다. 모든 버전의 단일 출처는 [`gradle/libs.versions.toml`](../gradle/libs.versions.toml)이다.

---

## 1. 한눈에 보기

| 층 | 선택 | 버전 | 한 줄 이유 |
| --- | --- | --- | --- |
| 언어 | Kotlin | 2.1.21 | 값 객체·sealed class·null 안전성이 도메인 모델링에 맞음 |
| 런타임 | JVM (Java) | 21 LTS | 가상 스레드 GA — 블로킹 코드로 높은 동시성 |
| 빌드 | Gradle Kotlin DSL | 8.14 | 멀티모듈 + 커스텀 검증 태스크 작성이 자유로움 |
| 프레임워크 | Spring Boot | 3.5.3 | 스케줄러·트랜잭션·Actuator·WS 클라이언트가 한 묶음 |
| 웹 | Spring MVC + 가상 스레드 | — | WebFlux 대신. ADR-003 |
| DB 접근 | Spring JDBC (`JdbcClient`) | — | JPA 대신. ADR-004 |
| DB | PostgreSQL | 16 | CHECK 제약·부분 인덱스·JSONB·트리거 |
| 캐시·락 | Redis | 7 | 최신가 캐시, 분산락, Pub/Sub 팬아웃 |
| 마이그레이션 | Flyway | Boot 관리 | SQL 그대로 씀. ADR-010 |
| 이벤트 | Redis Streams → Kafka | — | 단계적 도입. ADR-011 |
| 동시성 | kotlinx.coroutines | 1.10.2 | 종목별 액터(Channel) + 백프레셔 |
| 장애 내성 | Resilience4j | 2.3.0 | 서킷 브레이커·재시도·백오프 |
| 관측성 | Actuator + Micrometer + Prometheus | Boot 관리 | 메트릭 노출 |
| 구조화 로그 | logstash-logback-encoder | 8.1 | traceId·orderId를 JSON 필드로 |
| 테스트 | Kotest / JUnit 5 / Testcontainers | 5.9.1 / — / 1.21.3 | 도메인·통합 분리. ADR-015 |
| 목 | MockK | 1.14.3 | Kotlin 친화적 |
| 로컬 인프라 | Docker Compose | — | 죽이고 되살리는 실험이 가능해야 함 |
| 카오스 | Toxiproxy | 2.11.0 | 지연·패킷 손실 주입 (Phase 5) |

---

## 2. 모듈별 의존성

의존 방향은 항상 아래에서 위로만 흐른다. 역방향이 생기면 설계가 틀린 것이다.

```
core-support  (순수 Kotlin, 프레임워크 없음)
     ↑
core-domain   (순수 Kotlin, 프레임워크 없음 — 빌드가 강제)
     ↑
     ├── market-data    (Spring, Redis, WebSocket, 코루틴, Resilience4j)
     ├── trading        (Spring, JDBC, Redis, 코루틴)
     └── notification   (Spring, JDBC, Resilience4j)
              ↑
        api-gateway     (Spring Web, Actuator, Flyway — 유일한 실행 모듈)
```

| 모듈 | 역할 | 특징적인 의존성 | 없는 것과 그 이유 |
| --- | --- | --- | --- |
| `core-support` | 에러 코드, ID 생성기, Clock 헬퍼 | 없음 | Phase 0에선 자리만 잡음 |
| `core-domain` | 주문 상태 머신, 체결 규칙, `Money` | Kotest Property | **Spring 없음** — 빌드가 차단 |
| `market-data` | 시세 수집·집계·캐시 | 코루틴, WebSocket, Resilience4j | `starter-web` 없음 (서버 아님) |
| `trading` | 계좌·주문·체결·원장 | JDBC, 코루틴 | **JPA 없음** (ADR-004) |
| `notification` | Outbox, 발송 채널 | JDBC, Resilience4j | `starter-web` 없음 (클라이언트만) |
| `api-gateway` | REST + SSE, Flyway | Web, Actuator, Prometheus | — |

---

## 3. 왜 이 조합인가 — 핵심 4개만

전체 근거는 ADR에 있고, 여기서는 가장 영향이 큰 네 가지만 요약한다.

**① Spring MVC + 가상 스레드 (WebFlux 아님)** — 학습 목표가 리액티브 연산자 숙달이 아니라
락·트랜잭션 이해다. WebFlux는 `Mono`/`Flux` 체인 때문에 "어디서 락이 잡히고 어디서 트랜잭션이
끝나는지"를 읽기 어렵게 만든다. Java 21의 가상 스레드는 블로킹 코드를 그대로 쓰면서 수만 개
동시 요청을 처리하므로, 코드 가독성을 잃지 않고 확장성만 얻는다.

**② JPA 없음** — §06 C1·C2의 핵심 기법이 손으로 쓴 조건부 UPDATE와 `affected rows` 확인이다.
그런데 JPA의 자연스러운 사용법(`findById` → 필드 수정 → `save`)이 바로 C1이 터지는 코드다.
프레임워크와 싸우는 대신 `JdbcClient`로 SQL을 그대로 본다.

**③ PostgreSQL (MySQL 아님)** — 부분 인덱스(`WHERE status IN (...)`), 테이블 단위 CHECK 제약,
`RETURNING` 절, JSONB, plpgsql 트리거를 전부 쓴다. 불변식을 DB 레벨에서 방어하는 것이 이
프로젝트의 방식이므로 제약 표현력이 곧 설계 표현력이다.

**④ Testcontainers를 Phase 0부터** — 동시성 테스트는 mock DB로 검증할 수 없다. 조건부 UPDATE의
원자성, UNIQUE 제약, 데드락은 실제 PostgreSQL에서만 발생한다. Phase 3에서 급하게 붙이면 그때
이미 mock 기반 테스트가 쌓여 있어 되돌리기 어렵다.

---

## 4. 아직 도입하지 않은 것 (도입 시점 명시)

기술을 미리 넣지 않는 것도 결정이다. 필요해지는 시점을 적어두면 "언제 넣을지"를 매번 다시
고민하지 않는다.

| 기술 | 도입 시점 | 왜 지금은 아닌가 |
| --- | --- | --- |
| Kafka | Phase 3 후반, 인스턴스를 2대 이상 띄울 때 | 단일 인스턴스로도 C1~C6를 다 겪는다. 이벤트 발행 인터페이스만 추상화해둠 |
| jOOQ | 쿼리가 복잡해져 문자열 SQL 관리가 힘들어질 때 | 코드 생성 단계가 Phase 0을 무겁게 한다 |
| OpenTelemetry | Phase 6 | 추적할 흐름(주문→체결→알림)이 아직 없다 |
| Spring Security | 인증 붙일 때 (Q4) | 학습 초점이 아니다 |
| React 프론트엔드 | Phase 6 이후 (Q5) | SSE 테스트 페이지로 충분하다 |
| TimescaleDB | 분봉이 수억 행이 될 때 | 일반 PostgreSQL로 시작해 실제 병목을 확인한 뒤 |

---

## 5. 실행

```bash
# 1. 로컬 인프라 (PostgreSQL + Redis)
docker compose up -d
docker compose ps            # healthy 확인

# 2. 앱 실행
./gradlew :api-gateway:bootRun

# 3. 확인
curl -s localhost:8080/health | jq
curl -s localhost:8080/actuator/prometheus | head

# 4. 테스트
./gradlew :core-domain:check  # 도메인 단위·속성 테스트 + 순수성 검사 (Docker 불필요)
./gradlew check               # 전체. Testcontainers 사용 → Docker 데몬 필요

# 5. 카오스 테스트용 프록시 (Phase 5)
docker compose --profile chaos up -d
```

**환경변수** (기본값은 Compose 설정과 일치하므로 로컬에선 설정 불필요)

| 변수 | 기본값 |
| --- | --- |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5432` / `stockstudy` |
| `DB_USERNAME` / `DB_PASSWORD` | `stockstudy` / `stockstudy` |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` |
| `SERVER_PORT` | `8080` |

KIS API 키는 Phase 2에서 추가한다. **저장소에 커밋하지 않고** 환경변수로 주입한다.

---

## 6. 버전 올릴 때

`gradle/libs.versions.toml`의 `[versions]` 블록만 고친다. 모듈 빌드 파일에는 버전을 쓰지 않는다.

Spring Boot 버전을 올리면 BOM이 관리하는 라이브러리(Flyway, Jackson, Micrometer, PostgreSQL
드라이버 등)가 함께 움직인다. 그래서 카탈로그에서 이들의 버전을 명시하지 않았다 — BOM과
어긋나면 런타임에 미묘하게 깨진다.
