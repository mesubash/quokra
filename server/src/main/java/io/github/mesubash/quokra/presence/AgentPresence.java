package io.github.mesubash.quokra.presence;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// Who is answering chats right now. The console checks in every minute with the agent's chosen status;
// someone who closed it stops counting after a couple of minutes, without having to say so.
@Component
public class AgentPresence {

    public enum Status { ONLINE, AWAY }

    static final Duration GONE_AFTER = Duration.ofMinutes(2);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AgentPresence(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // A check-in. Without a status the agent keeps theirs (ONLINE the first time).
    public Status checkIn(String agentSub, Status status) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
            insert into agent_presence (agent_sub, status, last_seen) values (?, coalesce(?, 'ONLINE'), ?)
            on conflict (agent_sub) do update set status = coalesce(?, agent_presence.status), last_seen = ?
            """, agentSub, name(status), now, name(status), now);
        return Status.valueOf(jdbc.queryForObject("select status from agent_presence where agent_sub = ?",
            String.class, agentSub));
    }

    // Away from now on, e.g. when signing out.
    public void leave(String agentSub) {
        jdbc.update("update agent_presence set status = 'AWAY' where agent_sub = ?", agentSub);
    }

    // Which of these agents are online.
    public Set<String> online(Collection<String> agentSubs) {
        if (agentSubs.isEmpty()) {
            return Set.of();
        }
        List<String> found = jdbc.queryForList("""
            select agent_sub from agent_presence
            where agent_sub = any (?) and status = 'ONLINE' and last_seen > ?
            """, String.class, agentSubs.toArray(String[]::new), Timestamp.from(since()));
        return Set.copyOf(found);
    }

    // Whether anyone of this tenant's team is online.
    public boolean anyoneOnline(UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists (select 1 from tenant_agents a join agent_presence p on p.agent_sub = a.agent_sub
                           where a.tenant_id = ? and p.status = 'ONLINE' and p.last_seen > ?)
            """, Boolean.class, tenantId, Timestamp.from(since())));
    }

    private Instant since() {
        return clock.instant().minus(GONE_AFTER);
    }

    private static String name(Status status) {
        return status == null ? null : status.name();
    }
}
