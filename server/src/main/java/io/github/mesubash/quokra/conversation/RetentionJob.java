package io.github.mesubash.quokra.conversation;

import io.github.mesubash.quokra.attachment.FileStore;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

// Every night, deletes resolved chats older than each tenant's retention_months (within RetentionLimits):
// the conversation, its messages, events, notes and read markers, and its images. Contacts stay, so a
// blocked contact stays blocked. Open chats are never deleted, however old.
// ponytail: one instance does the work; with several instances, take a database lock around run().
@Slf4j
@Component
public class RetentionJob {

    static final int BATCH = 500;

    private record TenantRule(UUID id, String slug, int months) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final FileStore files;
    private final RetentionLimits limits;
    private final Clock clock;

    public RetentionJob(JdbcTemplate jdbc, TransactionTemplate tx, FileStore files, RetentionLimits limits,
                        Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.files = files;
        this.limits = limits;
        this.clock = clock;
    }

    // Returns how many conversations were deleted.
    @Scheduled(cron = "${quokra.retention.cron:0 0 3 * * *}")
    public int run() {
        int total = 0;
        List<TenantRule> rules = jdbc.query("select id, slug, retention_months from tenants",
            (rs, i) -> new TenantRule(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3)));
        for (TenantRule rule : rules) {
            int months = limits.effective(rule.months());
            if (months == 0) {
                continue;
            }
            Timestamp before = Timestamp.from(clock.instant().atOffset(ZoneOffset.UTC).minusMonths(months).toInstant());
            int deleted = 0;
            int batch;
            do {
                batch = deleteBatch(rule.id(), before);
                deleted += batch;
            } while (batch == BATCH);
            if (deleted > 0) {
                log.info("Deleted {} chats of {} older than {} months", deleted, rule.slug(), months);
            }
            total += deleted;
        }
        return total;
    }

    private int deleteBatch(UUID tenantId, Timestamp before) {
        List<UUID> ids = jdbc.queryForList("""
            select id from conversations
            where tenant_id = ? and status = 'RESOLVED' and last_message_at < ?
            limit ?
            """, UUID.class, tenantId, before, BATCH);
        if (ids.isEmpty()) {
            return 0;
        }
        UUID[] batch = ids.toArray(UUID[]::new);
        List<String> images = tx.execute(status -> {
            List<String> keys = jdbc.queryForList("""
                select attachment_key from messages where conversation_id = any (?) and attachment_key is not null
                """, String.class, (Object) batch);
            for (String table : List.of("conversation_reads", "conversation_notes", "conversation_events", "messages")) {
                jdbc.update("delete from " + table + " where conversation_id = any (?)", (Object) batch);
            }
            jdbc.update("delete from conversations where id = any (?)", (Object) batch);
            return keys;
        });
        // After the rows are gone: a file that fails to delete is only wasted space, never a dangling message.
        for (String key : images) {
            try {
                files.delete(key);
            } catch (RuntimeException e) {
                log.warn("Couldn't delete image {}", key, e);
            }
        }
        return ids.size();
    }
}
