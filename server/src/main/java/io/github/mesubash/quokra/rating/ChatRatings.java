package io.github.mesubash.quokra.rating;

import io.github.mesubash.quokra.common.ApiException;
import io.github.mesubash.quokra.conversation.Conversation;
import io.github.mesubash.quokra.conversation.CustomerConversationView.Rating;
import io.github.mesubash.quokra.conversation.Status;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// Customers rate a chat once it's closed, if an agent had it: thumbs up or down, then maybe a comment.
// The thumb can't change; the comment can be added once, later.
@Component
public class ChatRatings {

    public static final int MAX_COMMENT = 1000;
    static final int RECENT = 50;

    public record AgentSummary(String sub, String name, long positive, long total) {}

    public record Recent(UUID conversationId, String customerName, String agentName, boolean positive, String comment,
                         Instant createdAt) {}

    // percentPositive: rounded, null without ratings. recent: the newest comments.
    public record Summary(long positive, long total, Integer percentPositive, List<AgentSummary> agents,
                          List<Recent> recent) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ChatRatings(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Optional<Rating> of(UUID conversationId) {
        return jdbc.query("select positive, comment from chat_ratings where conversation_id = ?",
            (rs, i) -> new Rating(rs.getBoolean(1), rs.getString(2)), conversationId).stream().findFirst();
    }

    // The thumb of each rated conversation among these (true: up); unrated ones are left out.
    public java.util.Map<UUID, Boolean> thumbs(java.util.Collection<UUID> conversationIds) {
        if (conversationIds.isEmpty()) {
            return java.util.Map.of();
        }
        var result = new java.util.HashMap<UUID, Boolean>();
        jdbc.query("select conversation_id, positive from chat_ratings where conversation_id = any(?)",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(rs.getObject(1, UUID.class), rs.getBoolean(2)),
            (Object) conversationIds.toArray(UUID[]::new));
        return result;
    }

    public Rating rate(Conversation c, boolean positive, String comment) {
        if (c.getStatus() != Status.RESOLVED) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_RESOLVED", "Only a closed chat can be rated");
        }
        if (c.getAssigneeSub() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_AGENT", "Only a chat with our team can be rated");
        }
        String text = comment == null || comment.isBlank() ? null : comment.strip();
        int added = jdbc.update("""
            insert into chat_ratings (conversation_id, tenant_id, agent_sub, agent_name, positive, comment, created_at)
            values (?, ?, ?, ?, ?, ?, ?) on conflict (conversation_id) do nothing
            """, c.getId(), c.getTenantId(), c.getAssigneeSub(), c.getAssigneeName(), positive, text,
            Timestamp.from(clock.instant()));
        if (added == 1) {
            return new Rating(positive, text);
        }
        // Already rated: only a first comment, with the same thumb.
        if (text == null || jdbc.update(
                "update chat_ratings set comment = ? where conversation_id = ? and positive = ? and comment is null",
                text, c.getId(), positive) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_RATED", "This chat has already been rated");
        }
        return new Rating(positive, text);
    }

    public Summary summary(UUID tenantId, int days) {
        Timestamp since = Timestamp.from(clock.instant().minus(Duration.ofDays(days)));
        List<AgentSummary> agents = jdbc.query("""
            select agent_sub, (array_agg(agent_name order by created_at desc))[1],
                   count(*) filter (where positive), count(*)
            from chat_ratings where tenant_id = ? and created_at >= ?
            group by agent_sub order by count(*) desc, agent_sub
            """, (rs, i) -> new AgentSummary(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)),
            tenantId, since);
        List<Recent> recent = jdbc.query("""
            select r.conversation_id, c.customer_name, r.agent_name, r.positive, r.comment, r.created_at
            from chat_ratings r join conversations c on c.id = r.conversation_id
            where r.tenant_id = ? and r.created_at >= ? and r.comment is not null
            order by r.created_at desc limit ?
            """, (rs, i) -> new Recent(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getBoolean(4), rs.getString(5), rs.getTimestamp(6).toInstant()),
            tenantId, since, RECENT);
        long positive = agents.stream().mapToLong(AgentSummary::positive).sum();
        long total = agents.stream().mapToLong(AgentSummary::total).sum();
        Integer percent = total == 0 ? null : (int) Math.round(100.0 * positive / total);
        return new Summary(positive, total, percent, agents, recent);
    }
}
