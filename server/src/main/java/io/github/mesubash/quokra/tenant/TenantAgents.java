package io.github.mesubash.quokra.tenant;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// Which tenants an agent works on (table tenant_agents).
@Component
public class TenantAgents {

    private final JdbcTemplate jdbc;

    public TenantAgents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Member(String sub, String name) {}

    public List<UUID> tenantIdsFor(String agentSub) {
        return jdbc.queryForList("select tenant_id from tenant_agents where agent_sub = ?", UUID.class, agentSub);
    }

    // Agents of a tenant, for picking who to transfer a chat to. Names are from each agent's last sign-in.
    public List<Member> members(UUID tenantId) {
        return jdbc.query("select agent_sub, coalesce(name, agent_sub) from tenant_agents where tenant_id = ? order by 2",
            (rs, i) -> new Member(rs.getString(1), rs.getString(2)), tenantId);
    }

    public void rememberName(String agentSub, String name) {
        jdbc.update("update tenant_agents set name = ? where agent_sub = ? and name is distinct from ?",
            name, agentSub, name);
    }
}
