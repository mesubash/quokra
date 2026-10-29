-- Demo tenant uses the robot's colour, which is also Quokra's default.
update tenants set primary_color = '#4C4FBA' where slug = 'demo';
