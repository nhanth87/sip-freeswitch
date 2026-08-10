-- Pending OCS debit queue (grill G3 deferred debit — no double-debit by icid key).

CREATE TABLE IF NOT EXISTS sf_pending_charge (
    charge_key   VARCHAR(160) PRIMARY KEY,
    icid         VARCHAR(128),
    sip_call_id  VARCHAR(256),
    account      VARCHAR(128),
    destination  VARCHAR(256),
    usage_str    VARCHAR(32),
    state        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    enqueued_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    settled_at   TIMESTAMPTZ
);

COMMENT ON TABLE sf_pending_charge IS 'Deferred debit when SessionS/OCS down; claim by key before debit';
COMMENT ON COLUMN sf_pending_charge.state IS 'PENDING | CLAIMING | SETTLED';

CREATE INDEX IF NOT EXISTS idx_sf_pending_charge_state
    ON sf_pending_charge (state)
    WHERE state = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_sf_pending_charge_icid
    ON sf_pending_charge (icid)
    WHERE icid IS NOT NULL;
