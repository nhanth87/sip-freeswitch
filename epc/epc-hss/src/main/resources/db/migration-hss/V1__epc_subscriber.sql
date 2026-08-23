-- Standalone epc-hss HSS subscriber schema (postgres-epc / epc database).
-- Dedicated Flyway location db/migration-hss so this never collides with the
-- epc-host db/migration set when both are deployed to the same epc DB.
-- Never Elisa / H2 / SQLite; PostgreSQL only.
-- Ki/OPc are NOT stored here — key_ref only; material lives in SecretStore.
CREATE TABLE IF NOT EXISTS epc_subscriber (
    imsi                  VARCHAR(15)    PRIMARY KEY,
    msisdn                VARCHAR(20),
    enabled               BOOLEAN        NOT NULL DEFAULT TRUE,
    mcc                   VARCHAR(3)     NOT NULL,
    mnc                   VARCHAR(3)     NOT NULL,
    default_apn           VARCHAR(64)    NOT NULL,
    apns_csv              TEXT           NOT NULL,
    qos_qci               INT            NOT NULL DEFAULT 9,
    charging_char         INT            NOT NULL DEFAULT 0,
    sqn                   BIGINT         NOT NULL DEFAULT 0,
    serving_mme_host      TEXT,
    purged                BOOLEAN        NOT NULL DEFAULT FALSE,
    ims_scscf_uri         TEXT,
    impi                  TEXT,
    impu_csv              TEXT,
    service_profile_json  TEXT,
    ue_ambr_ul_bps        BIGINT,
    ue_ambr_dl_bps        BIGINT,
    key_ref               TEXT           NOT NULL,
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS epc_subscriber_msisdn ON epc_subscriber (msisdn);