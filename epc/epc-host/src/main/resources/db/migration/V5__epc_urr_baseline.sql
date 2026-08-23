-- URR charging-loop baselines (PFCP Usage Reporting Rules, TS 29.244). The UP
-- agent restarts with kernel counters intact but JVM memory lost; the persisted
-- baseline keeps the first poll after restart delta-correct (no double-debit).
CREATE TABLE IF NOT EXISTS epc_urr_baseline (
    urr_id TEXT PRIMARY KEY,
    baseline_bytes BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
