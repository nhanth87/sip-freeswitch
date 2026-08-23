-- PCRF durable stores (postgres-epc / epc database). Never Elisa, never H2/Derby/SQLite.
-- Column names are consumed by JdbcPolicyRepository (et.epc.pcrf).

-- PCC rule catalog. Rules are APN-scoped in the pilot (apn added for the
-- (imsi, apn) lookup; the decision snapshot per session lives in
-- epc_ipcan_session.decision_json). Non-secret policy only — no Ki/OPc here.
CREATE TABLE epc_pcc_rule (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(64) NOT NULL,
    apn             VARCHAR(64) NOT NULL,
    qci             INT NOT NULL,
    arp_priority    INT NOT NULL,
    preempt_cap     BOOLEAN NOT NULL,
    preempt_vuln    BOOLEAN NOT NULL,
    mbr_ul_bps      BIGINT NOT NULL,
    mbr_dl_bps      BIGINT NOT NULL,
    gbr_ul_bps      BIGINT NOT NULL,
    gbr_dl_bps      BIGINT NOT NULL,
    rating_group    INT,
    service_id      VARCHAR(64),
    flow_desc_json  TEXT NOT NULL,
    gating_open     BOOLEAN NOT NULL,
    UNIQUE (apn, name)
);

-- IP-CAN session + latest decision snapshot. decision_json is a compact
-- non-secret JSON snapshot (rules + usage threshold + default bearer QoS).
CREATE TABLE epc_ipcan_session (
    session_id      VARCHAR(255) PRIMARY KEY,
    imsi            VARCHAR(15) NOT NULL,
    apn             VARCHAR(64) NOT NULL,
    ue_ipv4         VARCHAR(45),
    charging_id     BIGINT NOT NULL,
    decision_json   TEXT NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX epc_ipcan_session_imsi_apn ON epc_ipcan_session (imsi, apn);

-- Rx AF-session binding state (media IP + service flow -> installed rule name).
CREATE TABLE epc_pcrf_af_session (
    af_session_id   VARCHAR(255) PRIMARY KEY,
    imsi            VARCHAR(15),
    ipcan_session_id VARCHAR(255),
    rule_name       VARCHAR(64) NOT NULL,
    state           VARCHAR(16) NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX epc_pcrf_af_session_imsi ON epc_pcrf_af_session (imsi);