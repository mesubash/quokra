# 6. Connect your identity provider

Agents sign in with your company's own login (any OIDC provider: Keycloak, Auth0, Okta, Entra ID, your
own). Quokra never sees passwords; it only checks the access tokens your dashboard hands it.

1. **In your identity provider:**
   - Create a role for support staff, `SUPPORT_AGENT`, and one for Quokra admins, `QUOKRA_ADMIN`.
   - Make access tokens include the audience `quokra-api` and the roles (in a claim like `roles` or
     `realm_access.roles`).
   - Give your dashboard a client that signs agents in (for a single-page app: public client with PKCE).
2. **On the Quokra server**, set:

   ```bash
   OIDC_ISSUER_URI=https://login.example.com/realms/main   # exactly the tokens' "iss"
   QUOKRA_AGENT_ROLES_CLAIM=realm_access.roles             # where the roles are, if not "roles"
   ```

   Optional: `OIDC_AUDIENCE`, `QUOKRA_AGENT_ROLE`, `QUOKRA_ADMIN_ROLE`, `QUOKRA_AGENT_NAME_CLAIM`, and
   `OIDC_JWKS_URI` if the provider has no discovery document.
3. **In your dashboard**, pass the signed-in agent's token: `getToken: () => auth.getAccessToken()`.
4. **Check it** with a real token: `GET /api/v1/agent/me` answers `200` with the agent's name; `401`
   means the token didn't pass (issuer, audience, signature or expiry), `403` that the role is missing.

The demo stack's Keycloak (`server/keycloak/quokra-realm.json`) is a working example of this setup.

Reference: [Connect your identity provider](../EMBEDDING.md#connect-your-identity-provider), [Deploying](../DEPLOY.md#3-settings).
