-- Dev only: the demo tenant uses the blue from the Quokra logo.
update tenants set primary_color = '#0051FB' where slug = 'demo';
