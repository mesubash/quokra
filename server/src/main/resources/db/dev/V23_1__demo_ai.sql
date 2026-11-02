-- Dev only: the demo company tries AI suggestions (they only work once the server has QUOKRA_AI_KEY).
update tenants set ai_enabled = true where slug = 'demo';
