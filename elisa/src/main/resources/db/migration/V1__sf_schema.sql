create table sf_tenant (
  id varchar(80) primary key, name varchar(160) not null, enabled boolean not null default true, created_at timestamptz not null default now()
);
create table sf_portal_user (
  username varchar(120) primary key, password_hash varchar(255) not null, role varchar(16) not null,
  tenant_id varchar(80) references sf_tenant(id), enabled boolean not null default true, created_at timestamptz not null default now()
);
create table sf_config_doc (
  id bigserial primary key, tenant_id varchar(80) references sf_tenant(id), kind varchar(48) not null,
  document text not null default '{}', updated_at timestamptz not null default now()
);
create table sf_gateway (
  id bigserial primary key, tenant_id varchar(80) references sf_tenant(id), name varchar(120) not null,
  profile varchar(64) not null default 'external', enabled boolean not null default true, config text not null default '{}'
);
create table sf_extension (
  id bigserial primary key, tenant_id varchar(80) references sf_tenant(id), extension varchar(64) not null,
  password_hash varchar(255), enabled boolean not null default true, unique(tenant_id, extension)
);
create table sf_dialplan_rule (
  id bigserial primary key, tenant_id varchar(80) references sf_tenant(id), name varchar(120) not null,
  destination varchar(255) not null, action varchar(255) not null, priority integer not null default 100, enabled boolean not null default true
);
create table sf_acl_entry (
  id bigserial primary key, list_name varchar(80) not null, cidr varchar(80) not null, action varchar(16) not null, enabled boolean not null default true
);
create table sf_cdr (
  id bigserial primary key, call_uuid varchar(128), payload text not null, created_at timestamptz not null default now()
);
create index sf_cdr_uuid_idx on sf_cdr(call_uuid);
create table sf_recording (
  id bigserial primary key, call_uuid varchar(128), path varchar(512) not null, duration_seconds integer, created_at timestamptz not null default now()
);
create table sf_conference_profile (
  id bigserial primary key, tenant_id varchar(80) references sf_tenant(id), name varchar(120) not null, config text not null default '{}'
);
create table sf_queue (
  id bigserial primary key, tenant_id varchar(80) references sf_tenant(id), name varchar(120) not null, strategy varchar(80) not null default 'longest-idle-agent', enabled boolean not null default true
);
