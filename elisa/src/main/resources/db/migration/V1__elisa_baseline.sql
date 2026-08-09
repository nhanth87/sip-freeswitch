-- Elisa baseline schema (PostgreSQL) — single Flyway migration (squashed V1–V5).
-- Fresh DB only. Existing labs that already applied V1–V5: drop/recreate DB or
-- flyway repair + baseline (see docs/agents/datastore.md).

-- ========== Tenants / portal ==========
create table sf_tenant (
  id varchar(80) primary key,
  name varchar(160) not null,
  enabled boolean not null default true,
  kind varchar(32) not null default 'LAB',
  http_api_key varchar(128),
  http_api_key_fp varchar(8),
  source_addr_alias varchar(64),
  reseller_id varchar(120),
  trunk_transport varchar(16),
  trunk_config text not null default '{}',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
comment on column sf_tenant.kind is 'LAB | ENTERPRISE | TRUNK';
comment on column sf_tenant.trunk_transport is 'SIP | WS | HTTP2 | HTTP3 when kind=TRUNK';
comment on column sf_tenant.reseller_id is 'OPS username that created this tenant (T14)';

create table sf_portal_user (
  username varchar(120) primary key,
  password_hash varchar(255) not null,
  role varchar(16) not null,
  tenant_id varchar(80) references sf_tenant(id),
  enabled boolean not null default true,
  display_name varchar(160),
  http_api_key varchar(128),
  http_api_key_fp varchar(8),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table sf_config_doc (
  id bigserial primary key,
  tenant_id varchar(80) references sf_tenant(id),
  kind varchar(48) not null,
  document text not null default '{}',
  updated_at timestamptz not null default now()
);

create table sf_gateway (
  id bigserial primary key,
  tenant_id varchar(80) references sf_tenant(id),
  name varchar(120) not null,
  profile varchar(64) not null default 'external',
  enabled boolean not null default true,
  config text not null default '{}'
);

create table sf_extension (
  id bigserial primary key,
  tenant_id varchar(80) references sf_tenant(id),
  extension varchar(64) not null,
  password_hash varchar(255),
  enabled boolean not null default true,
  unique (tenant_id, extension)
);

create table sf_dialplan_rule (
  id bigserial primary key,
  tenant_id varchar(80) references sf_tenant(id),
  name varchar(120) not null,
  destination varchar(255) not null,
  action varchar(255) not null,
  priority integer not null default 100,
  enabled boolean not null default true
);

create table sf_acl_entry (
  id bigserial primary key,
  list_name varchar(80) not null,
  cidr varchar(80) not null,
  action varchar(16) not null,
  enabled boolean not null default true
);
comment on table sf_acl_entry is 'SIP/FS ACL. list_name=sip-ingress: allow=approved peer, deny=block. Default policy deny when sf.sip.acl.enabled=true.';

-- ========== CDR (RestComm-shaped + profile correlate) ==========
create table sf_cdr (
  id bigserial primary key,
  call_uuid varchar(128),
  parent_call_uuid varchar(128),
  tenant_id varchar(80) references sf_tenant(id),
  trunk_tenant_id varchar(80) references sf_tenant(id),
  reseller_id varchar(120),
  source_addr varchar(64),
  date_created timestamptz not null default now(),
  date_updated timestamptz not null default now(),
  account_sid varchar(80),
  sender varchar(255),
  recipient varchar(64),
  phone_number_sid varchar(64),
  status varchar(32),
  start_time timestamptz,
  end_time timestamptz,
  duration integer,
  price varchar(16),
  price_unit varchar(8),
  direction varchar(20),
  answered_by varchar(64),
  api_version varchar(16),
  forwarded_from varchar(64),
  caller_name varchar(80),
  uri text,
  call_path varchar(255),
  ring_duration integer,
  instance_id varchar(255),
  conference_sid varchar(64),
  muted boolean,
  start_conference_on_enter boolean,
  end_conference_on_exit boolean,
  on_hold boolean,
  ms_id varchar(64),
  call_type varchar(34),
  hangup_cause varchar(64),
  sip_call_id varchar(255),
  msisdn varchar(32),
  payload text,
  created_at timestamptz not null default now()
);
create index sf_cdr_tenant_time_idx on sf_cdr (tenant_id, start_time desc nulls last);
create index sf_cdr_reseller_time_idx on sf_cdr (reseller_id, start_time desc nulls last);
create index sf_cdr_uuid_idx on sf_cdr (call_uuid);
create index sf_cdr_sip_call_id_idx on sf_cdr (sip_call_id);
create index sf_cdr_msisdn_idx on sf_cdr (msisdn);

create table sf_recording (
  id bigserial primary key,
  call_uuid varchar(128),
  path varchar(512) not null,
  duration_seconds integer,
  created_at timestamptz not null default now()
);

create table sf_conference_profile (
  id bigserial primary key,
  tenant_id varchar(80) references sf_tenant(id),
  name varchar(120) not null,
  config text not null default '{}'
);

create table sf_queue (
  id bigserial primary key,
  tenant_id varchar(80) references sf_tenant(id),
  name varchar(120) not null,
  strategy varchar(80) not null default 'longest-idle-agent',
  enabled boolean not null default true
);

-- ========== IVR ==========
create table sf_ivr_menu (
  id bigserial primary key,
  tenant_id varchar(80) not null references sf_tenant(id),
  name varchar(120) not null,
  greeting_prompt varchar(512),
  timeout_ms integer not null default 5000,
  max_failures integer not null default 3,
  xml_override text,
  enabled boolean not null default true,
  updated_at timestamptz not null default now(),
  unique (tenant_id, name)
);

create table sf_ivr_entry (
  id bigserial primary key,
  menu_id bigint not null references sf_ivr_menu(id) on delete cascade,
  digit varchar(8) not null,
  action varchar(64) not null,
  destination varchar(255),
  rank integer not null default 100,
  unique (menu_id, digit)
);

-- ========== Cluster C3 ==========
create table sf_cluster_node (
  id varchar(80) primary key,
  kind varchar(16) not null,
  address varchar(255) not null,
  sip_profile varchar(64),
  enabled boolean not null default true,
  meta text not null default '{}',
  updated_at timestamptz not null default now()
);

create table sf_fs_pair (
  elisa_node_id varchar(80) not null references sf_cluster_node(id),
  fs_node_id varchar(80) not null references sf_cluster_node(id),
  priority integer not null default 100,
  primary key (elisa_node_id, fs_node_id)
);
comment on table sf_fs_pair is 'C3 paired: Elisa-i primary FS + failover rows by priority';

-- ========== IMS profile (HSS-lite) + bindings ==========
create table ims_subscriber (
  id bigserial primary key,
  impi varchar(255) not null unique,
  impu varchar(255) not null,
  ki varchar(64) not null,
  opc varchar(64) not null,
  amf varchar(8) not null default '8000',
  sqn bigint not null default 0,
  ifc_json text,
  tenant_id varchar(80) references sf_tenant(id),
  msisdn varchar(32),
  display_name varchar(160),
  fs_a1_hash varchar(64),
  enabled boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index idx_ims_subscriber_impu on ims_subscriber(impu);
create index idx_ims_subscriber_msisdn on ims_subscriber(msisdn);
create index idx_ims_subscriber_tenant on ims_subscriber(tenant_id);
create unique index uq_ims_subscriber_tenant_msisdn
  on ims_subscriber(tenant_id, msisdn) where msisdn is not null and tenant_id is not null;
comment on table ims_subscriber is 'HSS-lite + IMS-first profile: IMPI/IMPU/Ki/OPc + MSISDN/tenant';
comment on column ims_subscriber.fs_a1_hash is 'Sofia directory a1-hash for AS/MRF (not IMS AKA Ki)';

create table ims_binding (
  id bigserial primary key,
  impu varchar(255) not null,
  contact varchar(512) not null,
  path_header text,
  home_scscf_node varchar(64) not null,
  expires_at timestamptz not null,
  call_id varchar(255),
  updated_at timestamptz not null default now(),
  unique (impu, contact)
);
create index idx_ims_binding_impu on ims_binding(impu);
create index idx_ims_binding_home on ims_binding(home_scscf_node);
comment on table ims_binding is 'S-CSCF registration bindings + home node sticky';

-- ========== Live call session (P2 PG mirror) ==========
create table sf_call_session (
  id bigserial primary key,
  fs_uuid varchar(128) not null,
  sip_call_id varchar(255),
  profile_id bigint references ims_subscriber(id),
  msisdn varchar(32),
  tenant_id varchar(80) references sf_tenant(id),
  elisa_node_id varchar(80),
  fs_node_id varchar(80),
  direction varchar(20),
  state varchar(32) not null default 'CREATED',
  started_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  ended_at timestamptz,
  unique (fs_uuid)
);
create index idx_sf_call_session_sip on sf_call_session(sip_call_id);
create index idx_sf_call_session_msisdn on sf_call_session(msisdn);
create index idx_sf_call_session_tenant on sf_call_session(tenant_id);
create index idx_sf_call_session_live on sf_call_session(ended_at) where ended_at is null;
comment on table sf_call_session is 'P2 dual-write live call correlate SIP Call-ID <-> FS Unique-ID';

-- ========== Dialog CMP checkpoint (ISPN hot mirror → PG durable) ==========
create table sf_dialog_checkpoint (
  sip_call_id varchar(255) primary key,
  peer varchar(255),
  from_uri text,
  to_uri text,
  trunk_uri text,
  fs_uuid varchar(128),
  msisdn varchar(32),
  fs_node_id varchar(80),
  elisa_node_id varchar(80),
  dialog_state varchar(32) not null default 'INVITE',
  started_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  ended_at timestamptz
);
create index idx_sf_dialog_checkpoint_fs on sf_dialog_checkpoint(fs_uuid);
create index idx_sf_dialog_checkpoint_msisdn on sf_dialog_checkpoint(msisdn);
create index idx_sf_dialog_checkpoint_live on sf_dialog_checkpoint(ended_at) where ended_at is null;
comment on table sf_dialog_checkpoint is 'CMP recovery for ScscfInviteSbb: dual-write with DialogCheckpoint ISPN';

-- ========== Seed: SIP ingress ACL (lab) ==========
insert into sf_acl_entry (list_name, cidr, action, enabled)
values
  ('sip-ingress', '127.0.0.1/32', 'allow', true),
  ('sip-ingress', '::1/128', 'allow', true),
  ('sip-ingress', '10.0.0.0/8', 'allow', true),
  ('sip-ingress', '192.168.0.0/16', 'allow', true);
