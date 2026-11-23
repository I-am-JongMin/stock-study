-- ============================================================================
-- V3 — 종목명 초성 컬럼 (§F1.2 — `ㅅㅅㅈㅈ` → 삼성전자)
--
-- 값은 애플리케이션이 Chosung.of() 로 계산해 넣는다 (core-domain).
--
-- **왜 생성 컬럼(GENERATED ALWAYS AS ... STORED)이 아닌가**
-- 그쪽이 DB 가 정합성을 보장해준다는 점에서 매력적이지만, IMMUTABLE 한 SQL 함수로
-- 초성 산술을 한 번 더 구현해야 한다. 같은 규칙의 구현이 Kotlin 과 PL/pgSQL 에
-- 두 벌 생기면 둘이 어긋나는 날이 오고, 어긋난 것을 알아챌 방법이 없다.
-- 규칙 자체가 유니코드 산술이라 바뀔 일이 거의 없다는 점도 고려했다.
-- 대신 §12 의 전수 테스트(음절 11,172 개)로 Kotlin 쪽 구현을 고정한다.
--
-- **왜 NOT NULL 인가**
-- NULL 을 허용하면 동기화 배치가 한 번 빠뜨렸을 때 그 종목이 검색에서 조용히
-- 사라진다. 조용한 누락보다 시끄러운 INSERT 실패가 낫다.
-- 지금 instrument 는 비어 있으므로 한 번에 NOT NULL 로 올릴 수 있다.
-- 운영 데이터가 쌓인 뒤였다면 nullable 로 추가 → 백필 → SET NOT NULL 3 단계가 필요하다.
-- ============================================================================

ALTER TABLE instrument
    ADD COLUMN name_chosung VARCHAR(128) NOT NULL;

COMMENT ON COLUMN instrument.name_chosung IS
    'Chosung.of(name) 의 결과. 검색어도 같은 함수를 지나야 한다 (§F1.2)';

-- 접두 검색(`name_chosung LIKE 'ㅅㅅ%'`)용.
--
-- varchar_pattern_ops 를 쓰는 이유 — 기본 btree 연산자 클래스는 DB 로케일의
-- 정렬 순서를 따르므로 C 로케일이 아니면 LIKE 'x%' 에 인덱스가 쓰이지 않는다.
-- 이 연산자 클래스는 바이트 순서로 비교해서 접두 검색이 인덱스를 탄다.
-- 대신 ORDER BY 에는 쓰이지 않으므로 정렬이 필요하면 일반 인덱스를 따로 둔다.
--
-- 솔직한 규모 감각 — KOSPI+KOSDAQ 는 2,800 종목 남짓이라 지금은 순차 스캔도 빠르다.
-- 이 인덱스는 §13 에서 미국 주식이 들어와 수만 행이 될 때를 위한 것이다.
CREATE INDEX idx_instrument_name_chosung
    ON instrument (name_chosung varchar_pattern_ops);
