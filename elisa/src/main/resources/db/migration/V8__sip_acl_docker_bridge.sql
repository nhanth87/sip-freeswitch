-- Compose / Docker bridge (nginx VIP → P-CSCF) is typically 172.16/12.
insert into sf_acl_entry (list_name, cidr, action, enabled)
select 'sip-ingress', '172.16.0.0/12', 'allow', true
where not exists (
  select 1 from sf_acl_entry
  where list_name = 'sip-ingress' and cidr = '172.16.0.0/12'
);
