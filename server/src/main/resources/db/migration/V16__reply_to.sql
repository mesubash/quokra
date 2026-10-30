-- A message can quote an earlier one in the same conversation. The quote is copied here (who wrote it and
-- a short preview), so it shows without looking the original up.
alter table messages add column reply_to_id uuid references messages;
alter table messages add column reply_to_sender text;
alter table messages add column reply_to_name text;
alter table messages add column reply_to_preview text;
