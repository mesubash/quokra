-- The website's own visitor id (e.g. its visitor logs), so a chat can be matched with the visits around it.
-- Only sent by sites that have the visitor's consent for analytics.
alter table conversations add column visit_site_visitor text;
