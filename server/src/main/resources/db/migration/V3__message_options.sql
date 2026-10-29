-- Menu buttons shown under a bot message, as a JSON list of { id, title }.
alter table messages add column options text;
