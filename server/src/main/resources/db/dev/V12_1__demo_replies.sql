-- Saved replies for the demo tenant, so typing "/" in the console shows something.
insert into canned_replies (id, tenant_id, title, body) values
    ('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-000000000001', 'Greeting',
     'Hi {name}, thanks for waiting. How can I help?'),
    ('00000000-0000-0000-0000-0000000000c2', '00000000-0000-0000-0000-000000000001', 'Looking into it',
     'Thanks {name}, I''m looking into this now and will get back to you in a few minutes.'),
    ('00000000-0000-0000-0000-0000000000c3', '00000000-0000-0000-0000-000000000001', 'Anything else',
     'Is there anything else I can help you with?');
