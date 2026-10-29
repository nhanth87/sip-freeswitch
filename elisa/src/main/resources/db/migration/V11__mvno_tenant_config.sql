-- MVNO console: per-tenant service config enforced on the S-CSCF INVITE path,
-- and the ADMIN > GD > USER hierarchy (jainslee-authz platform-admin > tenant-admin > user).
alter table sf_tenant add column if not exists max_cps integer not null default 0;
alter table sf_tenant add column if not exists max_concurrent integer not null default 0;
alter table sf_tenant add column if not exists msisdn_prefix varchar(32);
alter table sf_tenant add column if not exists isc_as_uri varchar(255);
alter table sf_tenant add column if not exists description varchar(500);
comment on column sf_tenant.max_cps is 'INVITE/s cap for the tenant''s originating subscribers; 0 = unlimited';
comment on column sf_tenant.max_concurrent is 'Live dialogs cap for the tenant; 0 = unlimited';
comment on column sf_tenant.msisdn_prefix is 'MSISDN range owned by the MVNO (provisioning check)';
comment on column sf_tenant.isc_as_uri is 'Per-tenant ISC AS / MRF override; blank = platform default';

alter table sf_portal_user add column if not exists msisdn varchar(32);
comment on column sf_portal_user.role is 'ADMIN | OPS | GD (tenant director) | USER (tenant member); legacy TENANT = GD';
comment on column sf_portal_user.msisdn is 'USER role: the subscriber this login owns';
