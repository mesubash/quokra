# Security

## Supported versions

The latest release gets security fixes. Older versions don't; upgrade to the latest (see the
[changelog](docs/CHANGELOG.md) and [Deploying](docs/DEPLOY.md)).

## Reporting a vulnerability

Please don't open a public issue. Report it privately through GitHub: **Security → Report a vulnerability**
on this repository. Include what you found, how to reproduce it, and what an attacker could do with it.

You'll get an answer within a week. Once it's fixed and released, the advisory is published, with credit
if you'd like it.

## Good to know

- The admin role (`QUOKRA_ADMIN`) operates the whole server: admins can see and change every company. Give
  it only to the people who run Quokra; company staff get the agent role.
- The demo profiles (`dev`, `demo`, `public-demo`) and the keys in this repository are public on purpose,
  for local use and demos. They are not vulnerabilities; never use them with real customers.
- How Quokra handles tokens, sessions, keys and embedding is described in
  [Architecture](docs/ARCHITECTURE.md) and [the embedding guide](docs/EMBEDDING.md).
