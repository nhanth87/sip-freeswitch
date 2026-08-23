-- EPC durable stores (postgres-epc / epc database). Never Elisa, never H2/Derby/SQLite.
-- Column names are consumed by JdbcSecretStore, JdbcCdrStore and JdbcDurableSink.

-- HSS/AuC secret material. Ki/OPc/AMF are AES-wrapped at rest (hex) with a KEK;
-- kek_version points at the wrapping key generation.
CREATE TABLE epc_secret (
    imsi           VARCHAR(15) PRIMARY KEY,
    ki_enc         TEXT NOT NULL,
    opc_enc        TEXT NOT NULL,
    amf_enc        TEXT NOT NULL,
    kek_version    INT NOT NULL DEFAULT 1,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- PCEF Gz CDR, durable billing record — never heap.
CREATE TABLE epc_pcef_cdr (
    charging_id     BIGINT PRIMARY KEY,
    imsi            VARCHAR(15) NOT NULL,
    apn             VARCHAR(64) NOT NULL,
    uplink_octets   BIGINT NOT NULL,
    downlink_octets BIGINT NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL,
    ended_at        TIMESTAMPTZ NOT NULL
);

-- Flattened SGW-C/PGW-C checkpoint, mirroring SessionCheckpoint record fields.
-- session_id is role-namespaced (sgw|imsi/apn/ebi). s11c_teid is null for pgw rows.
CREATE TABLE epc_session_checkpoint (
    session_id      VARCHAR(255) PRIMARY KEY,
    role            VARCHAR(16) NOT NULL,
    imsi            VARCHAR(15) NOT NULL,
    apn             VARCHAR(64) NOT NULL,
    ebi             INT NOT NULL,
    owner_node_id   VARCHAR(64) NOT NULL,
    generation      BIGINT NOT NULL,
    ue_ipv4         VARCHAR(45),
    sgw_s1u_key     VARCHAR(128),
    sgw_s5u_key     VARCHAR(128),
    pgw_s5u_key     VARCHAR(128),
    charging_id     BIGINT NOT NULL,
    pfcp_seid_hex   VARCHAR(32),
    s11c_teid       INT,
    pfcp_seid       BIGINT
);

CREATE INDEX epc_session_checkpoint_owner ON epc_session_checkpoint (owner_node_id);
CREATE INDEX epc_session_checkpoint_s11c ON epc_session_checkpoint (s11c_teid) WHERE role = 'sgw';