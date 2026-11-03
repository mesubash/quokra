-- Where a chat came from, for the team and marketing: the page it started on, the site and campaign that
-- brought the visitor, their country (from Cloudflare), device, language and time zone. No IP address.
alter table conversations
    add column visit_page         text,
    add column visit_referrer     text,
    add column visit_utm_source   text,
    add column visit_utm_medium   text,
    add column visit_utm_campaign text,
    add column visit_country      text,
    add column visit_device       text,
    add column visit_language     text,
    add column visit_time_zone    text;
