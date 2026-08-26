-- W2-a (mvno-improve-plan): IMS auth material at rest — AES-wrapped Ki/OPc.
-- Mirror of epc_secret: key_ref = IMPI, KEK from boot prop (sf.ims.secret.kek-hex).
-- Legacy plaintext ims_subscriber.ki / .opc are copied + wrapped by
-- ImsSecretMigrator at boot, then the columns are dropped (D6: drop at migration).
CREATE TABLE IF NOT EXISTS ims_secret (
    impi        varchar(255) PRIMARY KEY,
    ki_enc      varchar(128) NOT NULL,
    opc_enc     varchar(128) NOT NULL,
    kek_version int          NOT NULL DEFAULT 1,
    updated_at  timestamptz  NOT NULL DEFAULT now()
);
