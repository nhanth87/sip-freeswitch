-- GTP-C control peers of a session (TS 29.274 §5.5.2: a response carries the peer's TEID),
-- so any EPC node of the cluster can answer an S11 / S5-C request after a failover.
ALTER TABLE epc_session_checkpoint ADD COLUMN IF NOT EXISTS mme_s11_key varchar(64);
ALTER TABLE epc_session_checkpoint ADD COLUMN IF NOT EXISTS sgw_s5c_key varchar(64);
ALTER TABLE epc_session_checkpoint ADD COLUMN IF NOT EXISTS pgw_s5c_key varchar(64);
ALTER TABLE epc_session_checkpoint ADD COLUMN IF NOT EXISTS pgwc_peer varchar(64);
ALTER TABLE epc_session_checkpoint ADD COLUMN IF NOT EXISTS s5c_teid integer NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_epc_session_s5c_teid ON epc_session_checkpoint (s5c_teid) WHERE role = 'pgw';
