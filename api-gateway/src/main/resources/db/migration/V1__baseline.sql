-- ============================================================================
-- V1 — 베이스라인 스키마 (스펙 §05)
--
-- 이 파일의 핵심은 테이블이 아니라 **제약 조건**이다.
-- §03 의 불변식 7개는 애플리케이션 로직뿐 아니라 여기서도 방어된다.
-- 애플리케이션에 버그가 있어도 DB 가 마지막 방어선이 되어야 하고,
-- 제약 위반 예외가 발생하면 그것은 장애가 아니라 버그 리포트다.
--
-- ENUM 타입 대신 VARCHAR + CHECK 을 쓴다. Postgres ENUM 은 값 추가/삭제가
-- 마이그레이션으로 까다롭고, 상태 값은 앞으로 늘어난다.
-- ============================================================================

-- ─────────────────────────── 종목 · 시세 ───────────────────────────

CREATE TABLE instrument (
    code        VARCHAR(12)  PRIMARY KEY,           -- 국내 6자리, 미장은 심볼 (§13)
    market      VARCHAR(16)  NOT NULL,              -- KOSPI | KOSDAQ | NASDAQ ...
    name        VARCHAR(128) NOT NULL,
    currency    VARCHAR(3)   NOT NULL DEFAULT 'KRW',
    listed_at   DATE,
    delisted_at DATE,                               -- NULL 이면 상장 중. 삭제하지 않는다 (§F1)
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON COLUMN instrument.delisted_at IS
    '상장폐지 종목도 과거 주문이 참조하므로 행을 삭제하지 않고 이 값을 채운다 (§F1.3)';

CREATE INDEX idx_instrument_name ON instrument (name);
CREATE INDEX idx_instrument_market ON instrument (market) WHERE delisted_at IS NULL;

-- 거래일 캘린더는 코드에 하드코딩하지 않는다 (§F7.1).
-- 휴장일은 매년 바뀌고 임시 휴장도 발생한다.
CREATE TABLE trading_calendar (
    market     VARCHAR(16) NOT NULL,
    trade_date DATE        NOT NULL,
    is_open    BOOLEAN     NOT NULL,
    note       VARCHAR(64),
    PRIMARY KEY (market, trade_date)
);

CREATE TABLE daily_candle (
    code        VARCHAR(12) NOT NULL REFERENCES instrument (code),
    trade_date  DATE        NOT NULL,
    -- 수정주가 여부를 PK 에 포함해 원본가와 보정가를 함께 보관한다 (§01 함정 1).
    -- 보정 여부를 모른 채 저장하면 액면분할 종목의 과거 수익률이 조용히 망가진다.
    is_adjusted BOOLEAN     NOT NULL,
    open        BIGINT      NOT NULL CHECK (open  > 0),
    high        BIGINT      NOT NULL CHECK (high  > 0),
    low         BIGINT      NOT NULL CHECK (low   > 0),
    close       BIGINT      NOT NULL CHECK (close > 0),
    volume      BIGINT      NOT NULL CHECK (volume >= 0),
    PRIMARY KEY (code, trade_date, is_adjusted),
    CONSTRAINT candle_high_is_highest CHECK (high >= low AND high >= open AND high >= close),
    CONSTRAINT candle_low_is_lowest   CHECK (low  <= open AND low  <= close)
);

-- ─────────────────────────── 계좌 · 포지션 ───────────────────────────

CREATE TABLE account (
    id             BIGSERIAL   PRIMARY KEY,
    user_id        BIGINT      NOT NULL UNIQUE,
    currency       VARCHAR(3)  NOT NULL DEFAULT 'KRW',
    -- INV-1: 예수금은 절대 음수가 될 수 없다.
    -- C1 의 조건부 UPDATE 가 뚫리더라도 이 CHECK 이 트랜잭션을 실패시킨다.
    available_cash BIGINT      NOT NULL DEFAULT 0 CHECK (available_cash >= 0),
    reserved_cash  BIGINT      NOT NULL DEFAULT 0 CHECK (reserved_cash  >= 0),
    version        BIGINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON COLUMN account.available_cash IS
    'INV-1. 주문 가능 예수금. 최소 단위 정수(원/센트) — Money.minorUnits 와 1:1 대응';
COMMENT ON COLUMN account.reserved_cash IS
    '미체결 주문에 묶인 금액. available 에서 옮겨온 것이므로 합계는 보존된다';

CREATE TABLE position (
    account_id        BIGINT      NOT NULL REFERENCES account (id),
    code              VARCHAR(12) NOT NULL REFERENCES instrument (code),
    -- INV-3: 보유수량은 음수가 될 수 없다. 공매도를 지원하지 않으므로 예외가 없다.
    quantity          BIGINT      NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    reserved_quantity BIGINT      NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0),
    -- 평균단가 대신 총매입금액을 저장한다 (§06 C6).
    -- 나눗셈을 저장하면 read-modify-write 가 되어 경쟁이 생기지만,
    -- 덧셈으로 분해하면 조건부 UPDATE 한 방으로 원자적 갱신이 가능하다.
    total_cost        BIGINT      NOT NULL DEFAULT 0 CHECK (total_cost >= 0),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, code),
    CONSTRAINT position_reserved_within_holding CHECK (reserved_quantity <= quantity),
    -- 전량 매도 후 총매입금액에 잔여물이 남으면 평균단가가 무한이 된다 (§06 C6 참고).
    CONSTRAINT position_zero_qty_zero_cost CHECK ((quantity = 0) = (total_cost = 0))
);

-- ─────────────────────────── 주문 · 체결 ───────────────────────────

CREATE TABLE orders (   -- 'order' 는 SQL 예약어이므로 복수형을 쓴다
    id              BIGSERIAL   PRIMARY KEY,
    account_id      BIGINT      NOT NULL REFERENCES account (id),
    code            VARCHAR(12) NOT NULL REFERENCES instrument (code),
    side            VARCHAR(4)  NOT NULL CHECK (side IN ('BUY', 'SELL')),
    type            VARCHAR(8)  NOT NULL CHECK (type IN ('LIMIT', 'MARKET')),
    limit_price     BIGINT      CHECK (limit_price IS NULL OR limit_price > 0),
    quantity        BIGINT      NOT NULL CHECK (quantity > 0),
    filled_quantity BIGINT      NOT NULL DEFAULT 0 CHECK (filled_quantity >= 0),
    -- INV-7: 상태 머신(§05.1)이 허용한 값만 들어간다.
    status          VARCHAR(20) NOT NULL CHECK (status IN (
                        'PENDING', 'ACCEPTED', 'PARTIALLY_FILLED',
                        'FILLED', 'CANCELLED', 'REJECTED')),
    reject_reason   VARCHAR(64),
    -- INV-6: 멱등성 (§06 C4). 애플리케이션의 조회-후-삽입은 동시 요청에서 깨지므로
    -- UNIQUE 제약만이 신뢰할 수 있는 중복 방어다.
    idempotency_key VARCHAR(64) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- INV-5: 체결 수량이 주문 수량을 넘을 수 없다.
    CONSTRAINT orders_fill_within_quantity CHECK (filled_quantity <= quantity),
    -- 지정가는 가격이 필수, 시장가는 가격이 없어야 한다.
    CONSTRAINT orders_limit_price_matches_type CHECK ((type = 'LIMIT') = (limit_price IS NOT NULL)),
    CONSTRAINT orders_idempotency_unique UNIQUE (account_id, idempotency_key)
);

-- 오더북 재구성용 부분 인덱스 (§06 C3).
-- 미체결 주문만 담으므로 종료된 주문이 수백만 건 쌓여도 인덱스 크기가 커지지 않는다.
CREATE INDEX idx_orders_open_book ON orders (code, created_at)
    WHERE status IN ('ACCEPTED', 'PARTIALLY_FILLED');

CREATE INDEX idx_orders_history ON orders (account_id, created_at DESC);

CREATE TABLE execution (
    id               BIGSERIAL   PRIMARY KEY,
    order_id         BIGINT      NOT NULL REFERENCES orders (id),
    -- 가상 유동성 공급자와 체결된 경우 NULL (열린 질문 Q2 결정).
    -- 사용자 간 매칭이면 상대 주문 id 가 들어간다.
    counter_order_id BIGINT      REFERENCES orders (id),
    code             VARCHAR(12) NOT NULL REFERENCES instrument (code),
    side             VARCHAR(4)  NOT NULL CHECK (side IN ('BUY', 'SELL')),
    price            BIGINT      NOT NULL CHECK (price > 0),
    quantity         BIGINT      NOT NULL CHECK (quantity > 0),
    fee              BIGINT      NOT NULL DEFAULT 0 CHECK (fee >= 0),
    tax              BIGINT      NOT NULL DEFAULT 0 CHECK (tax >= 0),
    -- 해당 주문의 n 번째 부분체결. 재처리 시 중복 삽입을 막는다 (§06 C3).
    seq              INT         NOT NULL CHECK (seq > 0),
    executed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT execution_idempotent UNIQUE (order_id, seq)
);

COMMENT ON CONSTRAINT execution_idempotent ON execution IS
    'Kafka 리밸런싱 중 같은 메시지를 두 인스턴스가 처리해도 체결이 두 번 기록되지 않는다 (§06 C3)';

-- ─────────────────────────── 원장 ───────────────────────────

CREATE TABLE ledger (
    id            BIGSERIAL   PRIMARY KEY,
    account_id    BIGINT      NOT NULL REFERENCES account (id),
    entry_type    VARCHAR(24) NOT NULL CHECK (entry_type IN (
                      'DEPOSIT', 'WITHDRAW', 'RESERVE', 'RELEASE',
                      'TRADE_BUY', 'TRADE_SELL', 'FEE', 'TAX')),
    amount        BIGINT      NOT NULL,   -- 부호 있음. 출금은 음수
    balance_after BIGINT      NOT NULL,   -- 기록 시점 잔액. 감사(audit)용
    ref_type      VARCHAR(24) CHECK (ref_type IS NULL OR ref_type IN ('ORDER', 'EXECUTION')),
    ref_id        BIGINT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ledger_ref_pair CHECK ((ref_type IS NULL) = (ref_id IS NULL))
);

CREATE INDEX idx_ledger_account ON ledger (account_id, id);

-- INV-4: 원장은 append-only 다 (§05).
-- 잔고를 직접 UPDATE 하는 대신 원장에 쌓고 account 의 잔고 컬럼은 캐시로 취급한다.
-- 버그가 생겼을 때 "언제 어디서 돈이 새로 들어왔는지" 추적할 수 있는 유일한 방법이므로,
-- 트리거로 수정·삭제 자체를 불가능하게 만든다. 규율에 맡기지 않는다.
CREATE OR REPLACE FUNCTION ledger_reject_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        '원장은 append-only 입니다 (INV-4, 스펙 §05). % 연산은 허용되지 않습니다.', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_append_only
    BEFORE UPDATE OR DELETE ON ledger
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();

-- INV-2 정합성 검증을 SQL 한 번으로 할 수 있게 해두는 뷰.
-- Phase 6 에서 이 뷰의 drift <> 0 을 최고 우선순위 알람으로 걸 예정이다 (§03).
CREATE VIEW v_account_ledger_consistency AS
SELECT a.id                                                                  AS account_id,
       a.available_cash + a.reserved_cash                                    AS balance_columns,
       COALESCE(SUM(l.amount), 0)                                            AS ledger_sum,
       a.available_cash + a.reserved_cash - COALESCE(SUM(l.amount), 0)       AS drift
FROM account a
         LEFT JOIN ledger l ON l.account_id = a.id
GROUP BY a.id, a.available_cash, a.reserved_cash;

COMMENT ON VIEW v_account_ledger_consistency IS
    'INV-2. drift 가 0 이 아닌 계좌는 돈이 새고 있다는 뜻이다';

-- ─────────────────────────── 알림 ───────────────────────────

CREATE TABLE price_alert (
    id           BIGSERIAL   PRIMARY KEY,
    account_id   BIGINT      NOT NULL REFERENCES account (id),
    code         VARCHAR(12) NOT NULL REFERENCES instrument (code),
    target_price BIGINT      NOT NULL CHECK (target_price > 0),
    direction    VARCHAR(8)  NOT NULL CHECK (direction IN ('ABOVE', 'BELOW')),
    active       BOOLEAN     NOT NULL DEFAULT TRUE,
    triggered_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- §08 의 구간 조회를 DB 에서도 가능하게 하는 부분 인덱스.
-- 인메모리 TreeMap 을 재구성할 때 활성 알림만 가격순으로 읽어온다.
CREATE INDEX idx_price_alert_active ON price_alert (code, direction, target_price)
    WHERE active;

CREATE TABLE notification_outbox (
    id            BIGSERIAL    PRIMARY KEY,
    -- at-least-once 발송의 대가. 수신 측이 중복을 제거할 수 있도록 키를 함께 보낸다 (§R4).
    dedup_key     VARCHAR(128) NOT NULL UNIQUE,
    channel       VARCHAR(16)  NOT NULL CHECK (channel IN ('TELEGRAM', 'WEB_PUSH', 'EMAIL')),
    payload       JSONB        NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                               CHECK (status IN ('PENDING', 'SENT', 'DEAD')),
    retry_count   INT          NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    next_retry_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at       TIMESTAMPTZ,
    CONSTRAINT outbox_sent_has_timestamp CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);

-- 발송 대기 중인 것만 담는 부분 인덱스. 발송 완료 건이 쌓여도 폴링 비용이 늘지 않는다.
CREATE INDEX idx_outbox_due ON notification_outbox (next_retry_at)
    WHERE status = 'PENDING';
