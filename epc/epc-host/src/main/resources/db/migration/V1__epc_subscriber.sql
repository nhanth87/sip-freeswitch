-- Dedicated EPC HSS schema (postgres-epc). Never the Elisa database.
-- Ki/OPc are NOT stored here — key_ref only; material lives in HSM/SecretStore.

CREATE TABLE epc_subscriber (
    imsi              VARCHAR(15) PRIMARY KEY,
    msisdn            VARCHAR(20),
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    mcc               VARCHAR(3) NOT NULL,
    mnc               VARCHAR(3) NOT NULL,
    default_apn       VARCHAR(64) NOT NULL,
    apns_csv          TEXT NOT NULL,
    qos_qci           INT NOT NULL DEFAULT 9,
    charging_char     INT NOT NULL DEFAULT 0,
    sqn               BIGINT NOT NULL DEFAULT 0,
    serving_mme_host  TEXT,
    purged            BOOLEAN NOT NULL DEFAULT FALSE,
    ims_scscf_uri     TEXT,
    key_ref           TEXT NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX epc_subscriber_msisdn ON epc_subscriber (msisdn);
