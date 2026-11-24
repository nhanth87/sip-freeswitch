-- PCEF CDR kind (START / INTERIM / STOP). JdbcCdrStore has written cdr_kind since the P1 PCEF
-- phases, but no migration added the column: every PGW-C Create Session on Postgres failed with
-- "pcef cdr persist" (found on the S5-C wire run, 2026-11-24).
ALTER TABLE epc_pcef_cdr ADD COLUMN IF NOT EXISTS cdr_kind varchar(16) NOT NULL DEFAULT 'STOP';
