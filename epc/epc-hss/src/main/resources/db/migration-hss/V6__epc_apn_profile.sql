-- EPC HSS APN configuration profile (TS 23.008 §2.3): relational per-APN rows
-- that supersede the legacy epc_subscriber.apns_csv profile when present
-- (no rows for an IMSI -> JdbcHssRepository falls back to the CSV profile).
-- Runs against postgres-epc / epc only — never Elisa, never H2/SQLite.
-- No secret material here (Ki/OPc stay in epc_secret via key_ref).
-- Same-content copy of epc-host/src/main/resources/db/migration/V6__epc_apn_profile.sql
-- (identical checksum, so either Flyway set may own the shared epc DB history).

CREATE TABLE IF NOT EXISTS epc_apn_profile (
    imsi                      TEXT NOT NULL,
    apn                       TEXT NOT NULL,
    pdn_type                  TEXT NOT NULL DEFAULT 'IPV4',
    qci                       INT NOT NULL DEFAULT 9,
    arp_priority              INT NOT NULL DEFAULT 9,
    preemption_capability     BOOLEAN NOT NULL DEFAULT false,
    preemption_vulnerability  BOOLEAN NOT NULL DEFAULT true,
    apn_ambr_ul_bps           BIGINT NOT NULL DEFAULT 100000000,
    apn_ambr_dl_bps           BIGINT NOT NULL DEFAULT 100000000,
    charging_characteristics  INT NOT NULL DEFAULT 0,
    default_apn               BOOLEAN NOT NULL DEFAULT false,
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (imsi, apn)
);

CREATE INDEX IF NOT EXISTS epc_apn_profile_imsi ON epc_apn_profile (imsi);
