-- Tenants OTA-full + trunk on tenant; RestComm-shaped CDR; IVR menus; cluster pair stubs.

alter table sf_tenant
  add column if not exists kind varchar(32) not null default 'LAB',
  add column if not exists http_api_key varchar(128),
  add column if not exists http_api_key_fp varchar(8),
  add column if not exists source_addr_alias varchar(64),
  add column if not exists reseller_id varchar(120),
  add column if not exists trunk_transport varchar(16),
  add column if not exists trunk_config text not null default '{}',
  add column if not exists updated_at timestamptz not null default now();

comment on column sf_tenant.kind is 'LAB | ENTERPRISE | TRUNK';
comment on column sf_tenant.trunk_transport is 'SIP | WS | HTTP2 | HTTP3 when kind=TRUNK';
comment on column sf_tenant.reseller_id is 'OPS username that created this tenant (T14)';

alter table sf_portal_user
  add column if not exists display_name varchar(160),
  add column if not exists http_api_key varchar(128),
  add column if not exists http_api_key_fp varchar(8),
  add column if not exists updated_at timestamptz not null default now();

-- Replace minimal CDR with RestComm-shaped + Elisa scoping (keep payload).
alter table sf_cdr rename to sf_cdr_legacy_v1;

create table sf_cdr (
  id bigserial primary key,
  call_uuid varchar(128),
  parent_call_uuid varchar(128),
  tenant_id varchar(80) references sf_tenant(id),
  trunk_tenant_id varchar(80) references sf_tenant(id),
  reseller_id varchar(120),
  source_addr varchar(64),
  -- RestComm restcomm_call_detail_records analog
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
  payload text,
  created_at timestamptz not null default now()
);

create index sf_cdr_tenant_time_idx on sf_cdr (tenant_id, start_time desc nulls last);
create index sf_cdr_reseller_time_idx on sf_cdr (reseller_id, start_time desc nulls last);
create index sf_cdr_uuid_idx2 on sf_cdr (call_uuid);

insert into sf_cdr (call_uuid, payload, created_at, date_created, date_updated, sender, recipient, status, direction, uri, api_version)
select call_uuid, payload, created_at, created_at, created_at,
       coalesce(call_uuid, ''), '', 'completed', 'unknown', '', 'elisa'
from sf_cdr_legacy_v1;

drop table sf_cdr_legacy_v1;

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

-- Cluster: Elisa↔FS paired topology (C3)
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
