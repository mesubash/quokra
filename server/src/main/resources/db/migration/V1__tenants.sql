create table tenants (
    id               uuid primary key,
    slug             text not null unique,
    display_name     text not null,
    publishable_key  text not null unique,
    widget_origins   text[] not null default '{}',
    console_origins  text[] not null default '{}',
    logo_url         text,
    primary_color    text,
    bot_config       text not null,
    created_at       timestamptz not null default now()
);
