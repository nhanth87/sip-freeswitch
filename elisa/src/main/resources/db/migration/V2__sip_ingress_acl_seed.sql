-- SIP ingress ACL: only approved IPs (list sip-ingress, action=allow) when default=deny.
-- Lab: seed loopback allow so local SIPp/dev still works until operators add carrier CIDRs.
insert into sf_acl_entry (list_name, cidr, action, enabled)
select 'sip-ingress', '127.0.0.1/32', 'allow', true
where not exists (
  select 1 from sf_acl_entry where list_name = 'sip-ingress' and cidr = '127.0.0.1/32'
);
insert into sf_acl_entry (list_name, cidr, action, enabled)
select 'sip-ingress', '::1/128', 'allow', true
where not exists (
  select 1 from sf_acl_entry where list_name = 'sip-ingress' and cidr = '::1/128'
);
insert into sf_acl_entry (list_name, cidr, action, enabled)
select 'sip-ingress', '10.0.0.0/8', 'allow', true
where not exists (
  select 1 from sf_acl_entry where list_name = 'sip-ingress' and cidr = '10.0.0.0/8'
);
insert into sf_acl_entry (list_name, cidr, action, enabled)
select 'sip-ingress', '192.168.0.0/16', 'allow', true
where not exists (
  select 1 from sf_acl_entry where list_name = 'sip-ingress' and cidr = '192.168.0.0/16'
);

comment on table sf_acl_entry is 'SIP/FS ACL. list_name=sip-ingress: allow=approved peer, deny=block. Default policy deny when sf.sip.acl.enabled=true.';
