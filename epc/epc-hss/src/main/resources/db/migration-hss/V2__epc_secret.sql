-- Standalone epc-hss HSS/AuC secret store (postgres-epc / epc database).
-- Ki/OPc/AMF are AES-wrapped at rest (hex) with a KEK; kek_version points at the
-- wrapping key generation. Consumed by JdbcSecretStore.
CREATE TABLE IF NOT EXISTS epc_secret (
    imsi           VARCHAR(15) PRIMARY KEY,
    ki_enc         TEXT NOT NULL,
    opc_enc        TEXT NOT NULL,
    amf_enc        TEXT NOT NULL,
    kek_version    INT NOT NULL DEFAULT 1,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);