-- Clearwater-parity Phase A: IMS charging correlate columns on sf_cdr
alter table sf_cdr add column if not exists charging_vector varchar(512);
alter table sf_cdr add column if not exists impi varchar(255);
alter table sf_cdr add column if not exists impu varchar(255);
alter table sf_cdr add column if not exists cscf_role varchar(16);
alter table sf_cdr add column if not exists orig_ioi varchar(160);
alter table sf_cdr add column if not exists term_ioi varchar(160);

create index if not exists sf_cdr_impu_idx on sf_cdr (impu);
create index if not exists sf_cdr_impi_idx on sf_cdr (impi);

comment on column sf_cdr.charging_vector is 'Raw P-Charging-Vector header value';
comment on column sf_cdr.cscf_role is 'Lab: P|I|S (usually S for dialog CDR)';
comment on column sf_cdr.orig_ioi is 'P-Charging-Vector orig-ioi';
comment on column sf_cdr.term_ioi is 'P-Charging-Vector term-ioi';
