package io.github.mesubash.quokra.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

// Turns an agent's access token into a login. Providers put roles in different places,
// so the claim path is configurable (e.g. "roles" or "realm_access.roles").
@Component
public class AgentJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    public static final String AGENT_ROLE = "AGENT";

    private final String rolesClaim;
    private final String agentRole;
    private final String nameClaim;

    public AgentJwtConverter(@Value("${quokra.agents.roles-claim}") String rolesClaim,
                             @Value("${quokra.agents.role}") String agentRole,
                             @Value("${quokra.agents.name-claim:}") String nameClaim) {
        this.rolesClaim = rolesClaim;
        this.agentRole = agentRole;
        this.nameClaim = nameClaim;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        List<GrantedAuthority> authorities = roles(jwt).contains(agentRole)
            ? List.of(new SimpleGrantedAuthority("ROLE_" + AGENT_ROLE))
            : List.of();
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    public String displayName(Jwt jwt) {
        // The configured claim first (QUOKRA_AGENT_NAME_CLAIM), then the usual ones.
        for (String claim : List.of(nameClaim, "name", "preferred_username", "email")) {
            if (claim.isBlank()) {
                continue;
            }
            String value = jwt.getClaimAsString(claim);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return jwt.getSubject();
    }

    private Collection<?> roles(Jwt jwt) {
        Object value = jwt.getClaims();
        for (String part : rolesClaim.split("\\.")) {
            if (!(value instanceof Map<?, ?> map)) {
                return List.of();
            }
            value = map.get(part);
        }
        return value instanceof Collection<?> c ? c : List.of();
    }
}
