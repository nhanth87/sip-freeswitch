-- W3-b (mvno-improve-plan): per-APN EPS PCO overrides — P-CSCF / DNS CSV
-- handed to the UE in Create Session Response. Empty = fall back to
-- epc.pco.* env defaults (D2: Gm VIP, never a bare node IP).
ALTER TABLE epc_apn_profile ADD COLUMN IF NOT EXISTS pcscf_csv varchar(255);
ALTER TABLE epc_apn_profile ADD COLUMN IF NOT EXISTS dns_csv varchar(255);
