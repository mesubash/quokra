package io.github.mesubash.quokra.agent;

import java.sql.Array;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// What each agent has read (table conversation_reads). Only feeds the unread counts; it never changes a conversation.
@Component
class AgentReads {

    private final JdbcTemplate jdbc;

    AgentReads(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Message ids are UUIDv7, so "further" is simply "greater"; marking an older message changes nothing.
    void markRead(UUID conversationId, String agentSub, UUID messageId) {
        jdbc.update("""
            insert into conversation_reads (conversation_id, agent_sub, last_read_message_id) values (?, ?, ?)
            on conflict (conversation_id, agent_sub)
            do update set last_read_message_id = greatest(conversation_reads.last_read_message_id, excluded.last_read_message_id)
            """, conversationId, agentSub, messageId);
    }

    // Customer messages the agent hasn't read yet, per conversation. Conversations with none are left out.
    Map<UUID, Integer> unreadCounts(String agentSub, Collection<UUID> conversationIds) {
        Map<UUID, Integer> counts = new HashMap<>();
        if (conversationIds.isEmpty()) {
            return counts;
        }
        Array ids = jdbc.execute((ConnectionCallback<Array>) c -> c.createArrayOf("uuid", conversationIds.toArray()));
        jdbc.query("""
            select m.conversation_id, count(*)
            from messages m
            left join conversation_reads r on r.conversation_id = m.conversation_id and r.agent_sub = ?
            where m.conversation_id = any(?) and m.sender_type = 'CUSTOMER'
              and (r.last_read_message_id is null or m.id > r.last_read_message_id)
            group by m.conversation_id
            """, rs -> {
            counts.put(rs.getObject(1, UUID.class), rs.getInt(2));
        }, agentSub, ids);
        return counts;
    }
}
