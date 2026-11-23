-- ============================================================================
-- V2 — 원장 append-only 의 구멍 막기: TRUNCATE (INV-4, 스펙 §05)
--
-- V1 의 ledger_append_only 트리거는 BEFORE UPDATE OR DELETE ... FOR EACH ROW 다.
-- 행 단위 트리거이므로 TRUNCATE 에는 **아예 발화하지 않는다.**
-- TRUNCATE 는 행을 하나씩 지우는 것이 아니라 테이블 파일을 통째로 버리는
-- DDL 에 가까운 연산이라, 행 트리거도 DELETE 권한 검사도 우회한다.
--
-- Phase 0 검증에서 실제로 원장이 통째로 지워지는 것을 확인했다.
-- INV-4 를 "규율에 맡기지 않는다"고 선언해 놓고 가장 파괴적인 경로가 열려 있었다.
--
-- 한계 — 이것은 사고 방지턱이지 권한 경계가 아니다.
-- 테이블 소유자(지금은 애플리케이션이 쓰는 stockstudy 역할)는
-- ALTER TABLE ... DISABLE TRIGGER 로 끄거나 DROP TRIGGER 로 지울 수 있다.
-- 진짜 방어선은 소유권을 분리해 TRUNCATE/DDL 권한 자체를 제거하는 것이고,
-- 그것은 Phase 6 운영 준비에서 다룬다.
--
-- 범위 — 이 마이그레이션은 ledger 만 다룬다.
-- execution 에는 V1 에 append-only 트리거가 아예 없다는 것도 함께 발견했지만,
-- 체결을 불변으로 못박는 것은 버그 수정이 아니라 설계 결정이므로
-- ADR 로 따로 다루고 별도 마이그레이션으로 올린다 (열린 질문 Q7).
-- ============================================================================

-- V1 의 ledger_reject_mutation() 을 그대로 재사용한다.
-- TG_OP 에 'TRUNCATE' 가 들어오므로 에러 메시지는 손댈 필요가 없다.
CREATE TRIGGER ledger_no_truncate
    BEFORE TRUNCATE ON ledger
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_mutation();

COMMENT ON TRIGGER ledger_no_truncate ON ledger IS
    'INV-4. 행 트리거(ledger_append_only)가 잡지 못하는 TRUNCATE 경로를 막는다';
