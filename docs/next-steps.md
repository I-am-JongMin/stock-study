# 다음에 할 일

> 이어서 작업할 때 이 파일부터 읽는다. 마지막 갱신: Phase 0 완료 시점.

---

## 1. 지금 어디까지 왔나

| Phase | 상태 | 비고 |
| --- | --- | --- |
| 0 — 골격 | **완료 (Docker 검증만 남음)** | 아래 §2 참고 |
| 1 — 종목 + 일봉 | 미착수 | 다음 단계 |
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
- Testcontainers 기반 통합 테스트 12건 작성
- 문서: `spec.md`, `tech-stack.md`, `decisions.md`(ADR 16개)

### 검증한 것 / 못 한 것

```bash
./gradlew compileKotlin compileTestKotlin   # ✅ 통과
./gradlew :core-domain:check                # ✅ 10건 통과 (Docker 불필요)
./gradlew check                             # ⚠️ 미실행 — Docker 데몬이 꺼져 있었음
```

---

## 2. 다음 세션 첫 작업 — Phase 0 마무리 검증

Docker Desktop을 실행한 뒤 아래를 순서대로 확인한다. **여기서 실패하면 Phase 1로 넘어가지 않는다.**

```bash
# ① 통합 테스트 12건 — 불변식이 DB 레벨에서 실제로 막히는지
./gradlew check

# ② 로컬 스택 기동
docker compose up -d
docker compose ps                       # postgres, redis 모두 healthy

# ③ 앱 기동 후 헬스체크
./gradlew :api-gateway:bootRun          # 별도 터미널
curl -s localhost:8080/health | jq      # status: UP, db/redis 모두 UP

# ④ 스키마가 실제로 적용됐는지
docker compose exec postgres psql -U stockstudy -d stockstudy -c '\dt'
docker compose exec postgres psql -U stockstudy -d stockstudy \
  -c 'SELECT * FROM v_account_ledger_consistency;'

# ⑤ 원장 append-only 트리거 직접 확인 (배운 것을 손으로 만져보기)
docker compose exec postgres psql -U stockstudy -d stockstudy -c "
  INSERT INTO account (user_id, available_cash) VALUES (1, 1000000);
  INSERT INTO ledger (account_id, entry_type, amount, balance_after)
    VALUES (1, 'DEPOSIT', 1000000, 1000000);
  UPDATE ledger SET amount = 999 WHERE account_id = 1;   -- 실패해야 정상
"
```

**예상되는 문제**

| 증상 | 원인 | 대응 |
| --- | --- | --- |
| Testcontainers가 Docker를 못 찾음 | Docker Desktop 미실행 | 실행 후 재시도 |
| Ryuk 컨테이너 시작 실패 | Docker 소켓 권한 | `TESTCONTAINERS_RYUK_DISABLED=true`로 우회 (컨테이너 수동 정리 필요) |
| `/health`가 503 | Redis 또는 PG 미기동 | `docker compose ps`로 확인 |
| 포트 5432 충돌 | 로컬 PostgreSQL 실행 중 | 로컬 것을 끄거나 Compose 포트를 변경 |

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
