-- Demo tenant for local development only (loaded with the dev profile).
insert into tenants (id, slug, display_name, publishable_key, widget_origins, console_origins, primary_color, bot_config)
values ('00000000-0000-0000-0000-000000000001', 'demo', 'Demo Travel', 'pk_test_demo',
        '{http://localhost:5500,http://127.0.0.1:5500}', '{http://localhost:5500,http://127.0.0.1:5500}',
        '#0A7C66', 'demo');
