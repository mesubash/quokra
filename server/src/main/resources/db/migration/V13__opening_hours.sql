-- When people answer chats, as JSON: {"timezone": "Europe/Berlin", "days": {"MON": "09:00-17:00", ...}}.
-- Days left out are closed. Null means always open.
alter table tenants add column opening_hours text;
