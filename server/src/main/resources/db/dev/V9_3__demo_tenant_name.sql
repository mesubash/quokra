-- Dev only: the demo tenant's bot answers questions about Quokra, so the chat is named after it.
update tenants set display_name = 'Quokra' where slug = 'demo';
