-- RFC 5626-lite / Path flow: persist REGISTER transport source so MT INVITE
-- can reach a NAT'd UE. Contact URI stays as registered (RFC 3261).
-- PostgreSQL only — dedicated Elisa database (not USSDGW).

alter table ims_binding
  add column received_host varchar(255),
  add column received_port integer,
  add column received_transport varchar(16);

comment on column ims_binding.received_host is
  'REGISTER UDP/TCP source host (flow), not Contact host';
comment on column ims_binding.received_port is
  'REGISTER UDP/TCP source port';
comment on column ims_binding.received_transport is
  'REGISTER transport: UDP, TCP, or TLS';
