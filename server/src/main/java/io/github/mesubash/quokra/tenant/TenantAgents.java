package io.github.mesubash.quokra.tenant;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Which tenants an agent works on (table tenant_agents).
@Component
public class TenantAgents {

    private final JdbcTemplate jdbc;

    public TenantAgents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Member(String sub, String name) {}

    // admin: company admin of this tenant (see TenantAccess).
    public record Agent(String sub, String name, String email, boolean admin) {}

    public record Invite(String email, boolean admin, Instant createdAt) {}

    public enum Added { ADDED, INVITED }

    public List<UUID> tenantIdsFor(String agentSub) {
        return jdbc.queryForList("select tenant_id from tenant_agents where agent_sub = ?", UUID.class, agentSub);
    }

    // Agents of a tenant, for picking who to transfer a chat to. Names are from each agent's last sign-in.
    public List<Member> members(UUID tenantId) {
        return jdbc.query("select agent_sub, coalesce(name, agent_sub) from tenant_agents where tenant_id = ? order by 2",
            (rs, i) -> new Member(rs.getString(1), rs.getString(2)), tenantId);
    }

    // Name and (verified) email from the agent's latest sign-in.
    public void rememberProfile(String agentSub, String name, String verifiedEmail) {
        jdbc.update("""
            update tenant_agents set name = ?, email = coalesce(?::text, email)
            where agent_sub = ? and (name is distinct from ? or (?::text is not null and email is distinct from ?::text))
            """, name, verifiedEmail, agentSub, name, verifiedEmail, verifiedEmail);
    }

    // Turns invites for this verified email into memberships. Returns how many tenants were joined.
    public int acceptInvites(String agentSub, String name, String verifiedEmail) {
        int joined = jdbc.update("""
            insert into tenant_agents (tenant_id, agent_sub, name, email, admin)
            select tenant_id, ?, ?, ?, admin from agent_invites where lower(email) = lower(?)
            on conflict (tenant_id, agent_sub) do update set admin = tenant_agents.admin or excluded.admin
            """, agentSub, name, verifiedEmail, verifiedEmail);
        jdbc.update("delete from agent_invites where lower(email) = lower(?)", verifiedEmail);
        return joined;
    }

    public List<Agent> agents(UUID tenantId) {
        return jdbc.query("""
            select agent_sub, coalesce(name, agent_sub), email, admin from tenant_agents where tenant_id = ? order by 2
            """, (rs, i) -> new Agent(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)), tenantId);
    }

    public List<Invite> invites(UUID tenantId) {
        return jdbc.query("select email, admin, created_at from agent_invites where tenant_id = ? order by email",
            (rs, i) -> new Invite(rs.getString(1), rs.getBoolean(2), rs.getTimestamp(3).toInstant()), tenantId);
    }

    // Someone who has already signed in with this verified email is added straight away; anyone else is
    // invited and joins on their first sign-in. admin: as company admin (an existing admin stays one).
    @Transactional
    public Added addByEmail(UUID tenantId, String email, boolean admin, String invitedBy) {
        List<Agent> known = jdbc.query("""
            select agent_sub, name, email from tenant_agents where lower(email) = lower(?) limit 1
            """, (rs, i) -> new Agent(rs.getString(1), rs.getString(2), rs.getString(3), false), email);
        if (!known.isEmpty()) {
            Agent agent = known.getFirst();
            jdbc.update("""
                insert into tenant_agents (tenant_id, agent_sub, name, email, admin) values (?, ?, ?, ?, ?)
                on conflict (tenant_id, agent_sub) do update set admin = tenant_agents.admin or excluded.admin
                """, tenantId, agent.sub(), agent.name(), agent.email(), admin);
            return Added.ADDED;
        }
        jdbc.update("""
            insert into agent_invites (tenant_id, email, invited_by, admin) values (?, ?, ?, ?)
            on conflict (tenant_id, lower(email)) do update set admin = agent_invites.admin or excluded.admin
            """, tenantId, email, invitedBy, admin);
        return Added.INVITED;
    }

    public boolean setAdmin(UUID tenantId, String agentSub, boolean admin) {
        return jdbc.update("update tenant_agents set admin = ? where tenant_id = ? and agent_sub = ?",
            admin, tenantId, agentSub) > 0;
    }

    public boolean remove(UUID tenantId, String agentSub) {
        return jdbc.update("delete from tenant_agents where tenant_id = ? and agent_sub = ?", tenantId, agentSub) > 0;
    }

    public boolean removeInvite(UUID tenantId, String email) {
        return jdbc.update("delete from agent_invites where tenant_id = ? and lower(email) = lower(?)", tenantId, email) > 0;
    }
}
