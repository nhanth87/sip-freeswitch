-- IMS charging correlate (lab) — P-Charging-Vector icid-value
alter table sf_cdr add column if not exists icid varchar(64);
create index if not exists sf_cdr_icid_idx on sf_cdr (icid);
