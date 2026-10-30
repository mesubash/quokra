-- Email alerts, per tenant (sent only when SMTP is configured, see MailCheck).
-- alert_email: where "a chat is waiting" goes (comma-separated); null = the agents' own emails.
-- waiting_alert_minutes: how long a chat may wait with nobody online before that email (0 = never).
-- email_customers: email a customer an agent's reply they haven't seen.
alter table tenants add column alert_email text;
alter table tenants add column waiting_alert_minutes int not null default 10 check (waiting_alert_minutes >= 0);
alter table tenants add column email_customers boolean not null default false;

-- What has been emailed already, so nothing is sent twice.
alter table conversations add column waiting_alert_sent_at timestamptz;
alter table conversations add column customer_emailed_message_id uuid;
