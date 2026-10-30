-- The tenant's secret key, which the host backend uses to sign identity tokens.
-- Encrypted (AES-GCM, TENANT_SECRET_ENCRYPTION_KEY): the server must read it back to check signatures.
alter table tenants add column secret_key_enc bytea;
