package io.github.mesubash.quokra.conversation;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Every few minutes, closes conversations that have gone quiet for longer than their tenant's
// auto_resolve_minutes (0 = never): chats with the bot the customer left, and chats where the agent
// wrote last and the customer never answered. Waiting chats and unanswered customers stay open.
// ponytail: one instance does the work; with several instances, take a database lock around run().
@Slf4j
@Component
public class AutoResolver {

    static final int BATCH = 200;

    private final JdbcTemplate jdbc;
    private final ConversationService service;
    private final Clock clock;

    public AutoResolver(JdbcTemplate jdbc, ConversationService service, Clock clock) {
        this.jdbc = jdbc;
        this.service = service;
        this.clock = clock;
    }

    record Candidate(UUID id, int minutes) {}

    @Scheduled(fixedDelayString = "${quokra.auto-resolve.every:PT5M}", initialDelayString = "PT1M")
    public int run() {
        // Same rule as ConversationService.autoResolve, which checks each one again before closing it.
        List<Candidate> candidates = jdbc.query("""
            select c.id, t.auto_resolve_minutes
            from conversations c join tenants t on t.id = c.tenant_id
            cross join lateral (
                select m.sender_type from messages m
                where m.conversation_id = c.id and m.sender_type in ('CUSTOMER', 'AGENT')
                order by m.id desc limit 1) last
            where t.auto_resolve_minutes > 0
              and c.last_message_at < ?::timestamptz - make_interval(mins => t.auto_resolve_minutes)
              and (c.status = 'BOT' or (c.status = 'HUMAN' and last.sender_type = 'AGENT'))
            order by c.last_message_at
            limit ?
            """, (rs, i) -> new Candidate(rs.getObject(1, UUID.class), rs.getInt(2)),
            Timestamp.from(clock.instant()), BATCH);
        int closed = 0;
        for (Candidate c : candidates) {
            try {
                if (service.autoResolve(c.id(), Duration.ofMinutes(c.minutes()))) {
                    closed++;
                }
            } catch (ObjectOptimisticLockingFailureException e) {
                // Someone changed it at the same moment; the next run looks again.
            } catch (RuntimeException e) {
                log.warn("Couldn't auto-resolve conversation {}", c.id(), e);
            }
        }
        if (closed > 0) {
            log.info("Auto-resolved {} quiet conversations", closed);
        }
        return closed;
    }
}
