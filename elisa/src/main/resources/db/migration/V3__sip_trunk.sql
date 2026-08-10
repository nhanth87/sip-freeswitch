-- SIP trunk registry (USSDGW-inspired) — ISC/MRF/PSTN peers shared across N–N S-CSCF nodes.

CREATE TABLE IF NOT EXISTS sf_sip_trunk (
    id          VARCHAR(64)  PRIMARY KEY,
    name        VARCHAR(160) NOT NULL,
    peer_host   VARCHAR(256) NOT NULL,
    peer_port   INT          NOT NULL DEFAULT 5060,
    transport   VARCHAR(8)   NOT NULL DEFAULT 'UDP',
    sip_uri     VARCHAR(512) NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    tenant_id   VARCHAR(80),
    kind        VARCHAR(16)  NOT NULL DEFAULT 'ISC',
    priority    INT          NOT NULL DEFAULT 100,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE sf_sip_trunk IS 'ISC/MRF/PSTN SIP trunks; pick by kind+priority; PG SoT for N-N';
COMMENT ON COLUMN sf_sip_trunk.kind IS 'ISC | MRF | PSTN';

CREATE INDEX IF NOT EXISTS idx_sf_sip_trunk_kind_prio
    ON sf_sip_trunk (kind, priority)
    WHERE enabled = TRUE;

CREATE INDEX IF NOT EXISTS idx_sf_sip_trunk_tenant
    ON sf_sip_trunk (tenant_id);

-- Default FreeSWITCH ISC/MRF trunk (lab). Override via admin / props if empty table was intended.
INSERT INTO sf_sip_trunk (id, name, peer_host, peer_port, transport, sip_uri, enabled, kind, priority)
VALUES (
    'fs-default',
    'FreeSWITCH lab ISC/MRF',
    '127.0.0.1',
    5062,
    'UDP',
    'sip:1000@127.0.0.1:5062',
    TRUE,
    'ISC',
    10
)
ON CONFLICT (id) DO NOTHING;
