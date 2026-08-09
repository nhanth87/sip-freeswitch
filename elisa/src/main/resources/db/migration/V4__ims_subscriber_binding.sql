-- IMS Core: subscribers (HSS-lite) + registration bindings
create table if not exists ims_subscriber (
  id bigserial primary key,
  impi varchar(255) not null unique,
  impu varchar(255) not null,
  ki varchar(64) not null,
  opc varchar(64) not null,
  amf varchar(8) not null default '8000',
  sqn bigint not null default 0,
  ifc_json text,
  enabled boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists idx_ims_subscriber_impu on ims_subscriber(impu);

create table if not exists ims_binding (
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

create index if not exists idx_ims_binding_impu on ims_binding(impu);
create index if not exists idx_ims_binding_home on ims_binding(home_scscf_node);

comment on table ims_subscriber is 'HSS-lite: IMPI/IMPU + AKA Ki/OPc/SQN';
comment on table ims_binding is 'S-CSCF registration bindings + home node sticky';
