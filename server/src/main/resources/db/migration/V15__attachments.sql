-- An image sent in the chat. The file itself is in the file store (disk or S3) under attachment_key.
alter table messages add column attachment_key text;
alter table messages add column attachment_type text;
alter table messages add column attachment_size int;
alter table messages add column attachment_name text;

-- Whether customers and agents of this tenant may send images. Off until an admin turns it on.
alter table tenants add column attachments_enabled boolean not null default false;
