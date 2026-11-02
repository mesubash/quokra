package io.github.mesubash.quokra.security;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

// Who may manage which company. Server admins (the admin role) manage every company. A company admin
// manages only theirs: either marked as admin on their membership (Settings → Agents), or, when
// quokra.admins.company-claim is set, named in the token, e.g. "quokra:shop:admin" in "permissions".
// Also who may read which company's chats: its agents and its admins. Reading never allows acting on a chat;
// that takes the agent role and membership.
@Component
public class TenantAccess {

    private static final Pattern SLUG = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?");

    private final JdbcTemplate jdbc;
    private final String claim;
    private final String prefix;
    private final String suffix;

    public TenantAccess(JdbcTemplate jdbc,
                        @Value("${quokra.admins.company-claim:}") String claim,
                        @Value("${quokra.admins.company-value:quokra:{tenant}:admin}") String value) {
        int at = value.indexOf("{tenant}");
        if (at < 0) {
            throw new IllegalStateException("quokra.admins.company-value needs {tenant} in it, e.g. quokra:{tenant}:admin");
        }
        this.jdbc = jdbc;
        this.claim = claim.strip();
        this.prefix = value.substring(0, at);
        this.suffix = value.substring(at + "{tenant}".length());
    }

    public static boolean serverAdmin(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> ("ROLE_" + AgentJwtConverter.ADMIN_ROLE).equals(a.getAuthority()));
    }

    // Slugs of the existing companies this person is company admin of (not counting the server admin role).
    public Set<String> managed(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return Set.of();
        }
        String[] fromToken = fromToken(jwt).toArray(String[]::new);
        return new TreeSet<>(jdbc.queryForList("""
            select t.slug from tenants t
            where t.slug = any(?)
               or exists (select 1 from tenant_agents a where a.tenant_id = t.id and a.agent_sub = ? and a.admin)
            """, String.class, fromToken, jwt.getSubject()));
    }

    // Ids of the companies whose chats this person may read: where they're an agent (while they have the agent
    // role) and what they manage. Server admins read all.
    public Set<UUID> readable(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return Set.of();
        }
        if (serverAdmin(auth)) {
            return new HashSet<>(jdbc.queryForList("select id from tenants", UUID.class));
        }
        return new HashSet<>(jdbc.queryForList("""
            select t.id from tenants t
            where t.slug = any(?)
               or (? and exists (select 1 from tenant_agents a where a.tenant_id = t.id and a.agent_sub = ?))
            """, UUID.class, managed(auth).toArray(String[]::new), agent(auth), jwt.getSubject()));
    }

    // For reads under /agent: agents, and anyone else who can read at least one company.
    public boolean mayRead(Authentication auth) {
        return auth != null && (agent(auth) || !readable(auth).isEmpty());
    }

    public AuthorizationManager<RequestAuthorizationContext> anyReadable() {
        return (auth, context) -> new AuthorizationDecision(mayRead(auth.get()));
    }

    private static boolean agent(Authentication auth) {
        return auth.getAuthorities().stream()
            .anyMatch(a -> ("ROLE_" + AgentJwtConverter.AGENT_ROLE).equals(a.getAuthority()));
    }

    public boolean manages(Authentication auth, String slug) {
        return serverAdmin(auth) || managed(auth).contains(slug);
    }

    // For /admin/tenants/{slug}/**.
    public AuthorizationManager<RequestAuthorizationContext> forTenant() {
        return (auth, context) -> new AuthorizationDecision(manages(auth.get(), context.getVariables().get("slug")));
    }

    // For the rest of /admin: server admins, and company admins of at least one company.
    public AuthorizationManager<RequestAuthorizationContext> anyCompany() {
        return (auth, context) -> new AuthorizationDecision(serverAdmin(auth.get()) || !managed(auth.get()).isEmpty());
    }

    private List<String> fromToken(Jwt jwt) {
        if (claim.isEmpty()) {
            return List.of();
        }
        Object value = AgentJwtConverter.claim(jwt, claim);
        // A list (permissions, groups) or one space-separated string (scope).
        Collection<?> values = value instanceof Collection<?> c ? c
            : value instanceof String s ? List.of(s.split("\\s+")) : List.of();
        return values.stream()
            .filter(String.class::isInstance).map(String.class::cast)
            .filter(v -> v.length() > prefix.length() + suffix.length() && v.startsWith(prefix) && v.endsWith(suffix))
            .map(v -> v.substring(prefix.length(), v.length() - suffix.length()))
            .filter(s -> SLUG.matcher(s).matches())
            .toList();
    }
}
