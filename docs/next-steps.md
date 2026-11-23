# 다음에 할 일

> 이어서 작업할 때 이 파일부터 읽는다. 마지막 갱신: Phase 0 검증 완료 시점.

---

## 1. 지금 어디까지 왔나

| Phase | 상태 | 비고 |
| --- | --- | --- |
| 0 — 골격 | **완료 · 검증까지 통과** | 검증 기록은 아래 §2 |
| 1 — 종목 + 일봉 | 미착수 | **지금 할 차례.** KIS API 키 필요 |
| 2 — 실시간 시세 | 미착수 | KIS 계좌·API 키 필요 |
| 3 — 계좌·주문·체결 | 미착수 | 이 프로젝트의 정점 |
| 4~7 | 미착수 | |

### 완료한 것

- 멀티모듈 골격 6개 (`core-support`, `core-domain`, `market-data`, `trading`, `notification`, `api-gateway`)
- `checkDomainPurity` — `core-domain`에 프레임워크가 들어오면 빌드 실패. 추이적 의존성까지 잡는 것 확인
- `Money` 값 객체 + 테스트 10건 (속성 기반 3건 포함) — 전부 통과
- 베이스라인 스키마 `V1__baseline.sql` — 불변식 INV-1~7을 DB 제약으로 방어, 원장 append-only 트리거, 정합성 뷰
- `docker-compose.yml` — PostgreSQL 16 + Redis 7, Phase 5용 Toxiproxy는 `chaos` 프로파일
- `GET /health` 컨트롤러 (DB·Redis 상태에 따라 200/503)
- Testcontainers 기반 통합 테스트 13건 (`BaselineSchemaTest` 11 + `HealthEndpointTest` 2)
- 문서: `spec.md`, `tech-stack.md`, `decisions.md`(ADR 16개)

### 검증한 것

```bash
./gradlew compileKotlin compileTestKotlin   # ✅ 통과
./gradlew check                             # ✅ 23건 전부 통과 (통합 13 + 도메인 10)
```

---

## 2. Phase 0 검증 기록 — 전부 통과

Docker Desktop 기동 후 5단계를 순서대로 확인했고, 모두 기대대로 동작했다.

| 단계 | 확인한 것 | 결과 |
| --- | --- | --- |
| ① `./gradlew check` | 불변식이 DB 레벨에서 실제로 막히는지 | ✅ 23건 통과 |
| ② `docker compose up -d` | 로컬 스택 기동 | ✅ postgres·redis 모두 healthy |
| ③ `GET /health` | 앱 기동 + 의존성 상태 | ✅ 200, `db`/`redis` 모두 UP |
| ④ `\dt`, 정합성 뷰 | Flyway V1 적용 여부 | ✅ 10개 테이블 + `flyway_schema_history`, `v_account_ledger_consistency` 조회됨 |
| ⑤ 원장 append-only | 트리거가 손으로도 막는지 | ✅ UPDATE·DELETE 모두 거부, INV-1 음수 예수금도 거부 |

⑤에서 실제로 받은 에러 — 규율이 아니라 DB가 막고 있다는 증거다.

```
ERROR:  원장은 append-only 입니다 (INV-4, 스펙 §05). UPDATE 연산은 허용되지 않습니다.
ERROR:  원장은 append-only 입니다 (INV-4, 스펙 §05). DELETE 연산은 허용되지 않습니다.
ERROR:  new row for relation "account" violates check constraint "account_available_cash_check"
```

정합성 뷰도 데이터를 넣은 상태에서 `drift = 0`을 확인했다. 검증용으로 넣은 행은
`TRUNCATE`로 정리해 DB는 비어 있는 상태다.

### ⚠️ 검증 중 발견한 구멍 — `TRUNCATE`는 append-only를 통과한다

`ledger_append_only` 트리거는 `BEFORE UPDATE OR DELETE ... FOR EACH ROW`라서
**`TRUNCATE`에는 걸리지 않는다.** 위 ⑤ 정리 작업에서 원장 행이 그대로 지워진 것이
그 증거다. 운영에서 원장이 통째로 날아가는 경로가 열려 있다는 뜻이므로, 다음 중
하나로 막아야 한다.

1. `CREATE TRIGGER ledger_no_truncate BEFORE TRUNCATE ON ledger FOR EACH STATEMENT ...`
   — 같은 함수를 재사용하면 된다 (`TG_OP`가 `TRUNCATE`로 들어온다)
2. 애플리케이션 DB 역할에서 테이블 소유권을 분리해 DDL/TRUNCATE 권한 자체를 제거

1번이 싸고 즉효지만, 소유자(superuser)는 트리거를 끄고 지울 수 있으므로 2번이
진짜 방어선이다. **Phase 1 마이그레이션(`V2`)에 1번을 넣고, 2번은 Phase 6 운영
준비에서 다룬다.**

---

## 3. Phase 1 — 종목 + 일봉

**완료 조건** (§11)
- 종목마스터 동기화가 멱등하다 — 두 번 연달아 실행해도 종목 수가 늘지 않는다
- 일봉 조회 API 동작
- 초성 검색 동작 (`ㅅㅅㅈㅈ` → 삼성전자)

**작업 순서 (제안)**

1. **KIS 계좌 개설 + API 키 발급** — `cp .env.example .env` 후 `KIS_APP_KEY`,
   `KIS_APP_SECRET`, `KIS_ACCOUNT_NO`를 채운다. `.env`는 이미 `.gitignore`에 있고
   실제로 차단되는 것을 확인했다
2. `market-data`에 KIS REST 클라이언트 — 접근토큰 발급/캐시부터. 여기서 §R3(토큰 갱신 레이스)을
   미리 만나게 되므로, Redis 분산락을 이때 붙일지 Phase 2로 미룰지 결정
3. 종목마스터 동기화 배치 — `INSERT ... ON CONFLICT DO UPDATE`로 멱등성 확보
4. 초성 검색 — `instrument`에 `name_chosung` 컬럼을 추가하는 마이그레이션 `V2`.
   한글 초성 추출은 `core-domain`의 순수 함수로 (유니코드 `0xAC00` 기준 산술)
5. 일봉 수집 — **`is_adjusted` 값을 반드시 채운다.** 수정주가 여부를 모르고 저장하면
   §01 함정 1에 그대로 걸린다
6. 조회 API 2개 — `GET /instruments?q=`, `GET /instruments/{code}/candles`

**이 단계에서 배울 것** — 외부 API 연동, 배치 멱등성, 수정주가 함정

**주의** — 호가단위·거래세 값은 스펙 §01.2에 참고용으로만 적혀 있다. Phase 1에서 KRX 공식
자료로 검증하고 설정 파일 또는 DB 테이블로 넣는다. 코드에 상수로 박지 않는다.

---

## 4. 아직 열려 있는 질문

결정된 것 (스펙 §14 갱신 필요)
- **Q1 시세 소스** → KIS Open API. 계좌 개설 예정 (ADR-012)
- **Q2 체결 상대방** → 가상 유동성 공급자 (ADR-002)

미결정
- **Q3 장외 시간 주문** — 예약 접수 후 개장 시 처리 / 즉시 거부. *Phase 3에서 결정*
- **Q4 인증** — 자체 JWT / OAuth. *Phase 3에서 계좌를 사용자와 묶을 때 결정*
- **Q5 프론트엔드** — 없음(SSE 테스트 페이지) / React. *Phase 2에서 SSE를 눈으로 봐야 할 때 결정*
- **Q6 이벤트 버스** → Redis Streams로 시작 (ADR-011). Kafka 전환은 인스턴스 2대 이상 띄울 때

---

## 5. 문서 지도

| 문서 | 내용 | 언제 읽나 |
| --- | --- | --- |
| [`spec.md`](./spec.md) | 요구사항·불변식·동시성 6케이스·장애 7개·로드맵 | 무엇을 만들지 확인할 때 |
| [`decisions.md`](./decisions.md) | ADR 16개 — 왜 그렇게 정했는지 | "왜 JPA를 안 썼지?" 같은 의문이 들 때 |
| [`tech-stack.md`](./tech-stack.md) | 스택·버전·모듈별 의존성·실행 방법 | 버전을 올리거나 라이브러리를 추가할 때 |
| `next-steps.md` | 이 파일 | 작업을 이어갈 때 **가장 먼저** |

**갱신 규칙** — 구현 중 결정이 바뀌면 코드보다 문서를 먼저 고친다. 새 결정을 내리면
`decisions.md`에 ADR을 추가하고, Phase가 끝나면 이 파일의 §1을 갱신한다.
