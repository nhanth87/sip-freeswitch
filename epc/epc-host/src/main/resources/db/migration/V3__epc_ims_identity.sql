-- EPC IMS identity (Cx) columns + iFC store. Copy to epc-host/src/main/resources/db/migration/ for Flyway.
-- Never Elisa/H2/SQLite: this runs against postgres-epc / epc only.
ALTER TABLE epc_subscriber
    ADD COLUMN IF NOT EXISTS impi                  TEXT,
    ADD COLUMN IF NOT EXISTS impu_csv              TEXT,
    ADD COLUMN IF NOT EXISTS service_profile_json  TEXT,
    ADD COLUMN IF NOT EXISTS ue_ambr_ul_bps        BIGINT,
    ADD COLUMN IF NOT EXISTS ue_ambr_dl_bps        BIGINT;

CREATE TABLE IF NOT EXISTS epc_ims_ifc (
    impi        TEXT PRIMARY KEY,
    ifc_json    TEXT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);