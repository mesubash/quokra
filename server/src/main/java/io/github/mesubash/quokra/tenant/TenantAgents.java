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

    public List<UUID> tenantIdsFor(String agentSub) {
        return jdbc.queryForList("select tenant_id from tenant_agents where agent_sub = ?", UUID.class, agentSub);
    }
}
