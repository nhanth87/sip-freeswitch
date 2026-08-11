-- MMTel-lite (Clearwater Homer-class settings without full XDMS)
create table if not exists ims_mmtel_settings (
  id bigserial primary key,
  impu varchar(255) not null unique,
  barring_mo boolean not null default false,
  barring_mt boolean not null default false,
  diversion_uri varchar(512),
  privacy_id boolean not null default false,
  document text not null default '{}',
  updated_at timestamptz not null default now()
);

create index if not exists ims_mmtel_settings_impu_idx on ims_mmtel_settings (impu);
comment on table ims_mmtel_settings is 'Lab MMTel: call barring / diversion / privacy (OIP/OIR lite)';
