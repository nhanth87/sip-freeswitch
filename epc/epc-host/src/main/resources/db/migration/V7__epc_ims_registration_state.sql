-- EPC IMS IMPU registration state machine (Cx, TS 29.228 / TS 23.002).
-- Copy to epc-host/src/main/resources/db/migration/ for Flyway.
-- Never Elisa/H2/SQLite: this runs against postgres-epc / epc only.
--
-- impu_reg_state: NOT_REGISTERED / REGISTERED / UNREGISTERED.
--   REGISTERED      — SAR REGISTRATION / RE_REGISTRATION (S-CSCF assigned).
--   UNREGISTERED    — server-name retained (SAR UNREGISTERED_USER, TIMEOUT/USER
--                     DEREGISTRATION_STORE_SERVER_NAME) for unregistered services.
--   NOT_REGISTERED  — deregistration / RTR; scscf_uri cleared.
-- scscf_uri: registered S-CSCF for the IMPU set alongside the state; kept in
--            lockstep with the pre-existing ims_scscf_uri column (V1).
ALTER TABLE epc_subscriber
    ADD COLUMN IF NOT EXISTS impu_reg_state TEXT NOT NULL DEFAULT 'NOT_REGISTERED',
    ADD COLUMN IF NOT EXISTS scscf_uri      TEXT;

ALTER TABLE epc_subscriber
    DROP CONSTRAINT IF EXISTS epc_subscriber_impu_reg_state_ck,
    ADD CONSTRAINT epc_subscriber_impu_reg_state_ck
        CHECK (impu_reg_state IN ('NOT_REGISTERED', 'REGISTERED', 'UNREGISTERED'));

COMMENT ON COLUMN epc_subscriber.impu_reg_state IS
    'IMPU registration state (TS 23.002): NOT_REGISTERED / REGISTERED / UNREGISTERED';
COMMENT ON COLUMN epc_subscriber.scscf_uri IS
    'S-CSCF stored with the IMPU registration state (Cx SAR/RTR); retained while UNREGISTERED when server-name is stored';
