-- Portable SIP HA fields for mid-dialog DialogRegistry restore (lab N–N).
alter table sf_dialog_checkpoint add column if not exists remote_peer varchar(255);
alter table sf_dialog_checkpoint add column if not exists transport varchar(16);
alter table sf_dialog_checkpoint add column if not exists remote_transport varchar(16);
alter table sf_dialog_checkpoint add column if not exists cseq bigint not null default 0;
alter table sf_dialog_checkpoint add column if not exists from_tag varchar(128);
alter table sf_dialog_checkpoint add column if not exists to_tag varchar(128);
alter table sf_dialog_checkpoint add column if not exists contact_uri text;
alter table sf_dialog_checkpoint add column if not exists remote_contact_uri text;
alter table sf_dialog_checkpoint add column if not exists sip_method varchar(32);
alter table sf_dialog_checkpoint add column if not exists far_from_uri text;
alter table sf_dialog_checkpoint add column if not exists far_from_tag varchar(128);
comment on column sf_dialog_checkpoint.remote_peer is 'Far trunk host:port for DialogRegistry.restorePortable';
comment on column sf_dialog_checkpoint.from_tag is 'UA From-tag for synthetic lastRequest rebuild';
