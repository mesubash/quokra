-- Dev only: the demo company tries AI answers too (only with QUOKRA_AI_KEY on the server).
update tenants set ai_answers_enabled = true where slug = 'demo';
