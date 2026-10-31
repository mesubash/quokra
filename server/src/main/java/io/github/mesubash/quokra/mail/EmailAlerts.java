package io.github.mesubash.quokra.mail;

import io.github.mesubash.quokra.presence.AgentPresence;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Every minute, while email works (MailCheck):
// - a chat that has waited longer than the tenant's waiting_alert_minutes while nobody of the team is
//   online: one email to the team (alert_email, or the agents' own emails);
// - an agent's reply the customer hasn't answered for a few minutes: one email to the customer, if the
//   tenant has email_customers on and the customer left an email (and isn't blocked).
// Each is sent once; what was sent is marked on the conversation.
// ponytail: one instance does the work; with several instances, take a database lock around run().
@Slf4j
@Component
public class EmailAlerts {

    static final Duration UNSEEN_AFTER = Duration.ofMinutes(5);
    // Older replies aren't worth an email any more (e.g. after email was just turned on).
    static final Duration TOO_OLD = Duration.ofHours(24);
    static final int BATCH = 100;

    private record Waiting(UUID id, UUID tenantId, String company, String customer, String alertEmail,
                           Timestamp waitingSince) {}

    private record Unseen(UUID id, String company, String email, UUID emailedUpTo) {}

    private final JdbcTemplate jdbc;
    private final Mailer mailer;
    private final MailCheck check;
    private final Clock clock;

    public EmailAlerts(JdbcTemplate jdbc, Mailer mailer, MailCheck check, Clock clock) {
        this.jdbc = jdbc;
        this.mailer = mailer;
        this.check = check;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${quokra.mail.every:PT1M}", initialDelayString = "PT1M")
    public void run() {
        if (!check.ok()) {
            return;
        }
        alertWaiting();
        emailUnseenReplies();
    }

    // Returns how many chats the team was told about.
    int alertWaiting() {
        Instant now = clock.instant();
        List<Waiting> waiting = jdbc.query("""
            select c.id, c.tenant_id, t.display_name, c.customer_name, t.alert_email, c.waiting_since
            from conversations c join tenants t on t.id = c.tenant_id
            where c.status = 'WAITING' and t.waiting_alert_minutes > 0
              and c.waiting_since < ?::timestamptz - make_interval(mins => t.waiting_alert_minutes)
              and (c.waiting_alert_sent_at is null or c.waiting_alert_sent_at < c.waiting_since)
              -- Someone of the team is online and sees it in the console. Left out here, not just skipped below,
              -- so one busy tenant can't keep filling the batch and starve the others.
              and not exists (select 1 from tenant_agents a join agent_presence p on p.agent_sub = a.agent_sub
                              where a.tenant_id = c.tenant_id and p.status = 'ONLINE' and p.last_seen > ?)
            order by c.waiting_since
            limit ?
            """, (rs, i) -> new Waiting(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getTimestamp(6)),
            Timestamp.from(now), Timestamp.from(now.minus(AgentPresence.GONE_AFTER)), BATCH);
        int sent = 0;
        for (Waiting w : waiting) {
            List<String> to = w.alertEmail() != null && !w.alertEmail().isBlank()
                ? Arrays.stream(w.alertEmail().split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList()
                : jdbc.queryForList("select email from tenant_agents where tenant_id = ? and email is not null",
                    String.class, w.tenantId());
            long minutes = Duration.between(w.waitingSince().toInstant(), now).toMinutes();
            String who = w.customer() != null ? w.customer() : "A customer";
            boolean done;
            if (to.isEmpty()) {
                log.warn("Nobody to tell about a waiting chat of {}: no alert email, no agent emails", w.company());
                // Marked anyway, so a missing address doesn't mean trying every minute.
                done = true;
            } else {
                done = mailer.send(to, w.company() + ": a chat is waiting", """
                    %s has been waiting for %d minutes, and nobody of the team is online.

                    Open the console to answer.
                    """.formatted(who, minutes));
                if (done) {
                    sent++;
                }
            }
            // A failed send (mail server down) is tried again on the next run.
            if (done) {
                jdbc.update("update conversations set waiting_alert_sent_at = ? where id = ?", Timestamp.from(now), w.id());
            }
        }
        return sent;
    }

    // Returns how many customers were emailed.
    int emailUnseenReplies() {
        Instant now = clock.instant();
        List<Unseen> unseen = jdbc.query("""
            select c.id, t.display_name, c.customer_email, c.customer_emailed_message_id
            from conversations c join tenants t on t.id = c.tenant_id
            cross join lateral (
                select m.id, m.sender_type, m.created_at from messages m
                where m.conversation_id = c.id and m.sender_type in ('CUSTOMER', 'AGENT')
                order by m.id desc limit 1) last
            left join contacts k on k.id = c.contact_id
            where t.email_customers and c.customer_email is not null and coalesce(k.blocked_at is null, true)
              and last.sender_type = 'AGENT' and last.created_at < ? and last.created_at > ?
              and c.customer_emailed_message_id is distinct from last.id
            limit ?
            """, (rs, i) -> new Unseen(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getObject(4, UUID.class)),
            Timestamp.from(now.minus(UNSEEN_AFTER)), Timestamp.from(now.minus(TOO_OLD)), BATCH);
        int sent = 0;
        for (Unseen u : unseen) {
            // What the team wrote since the customer's last message, and since the last email.
            List<Reply> replies = jdbc.query("""
                select id, sender_name, body, attachment_key is not null from messages
                where conversation_id = ? and sender_type = 'AGENT'
                  and id > greatest(
                      coalesce((select id from messages where conversation_id = ? and sender_type = 'CUSTOMER'
                                order by id desc limit 1), '00000000-0000-0000-0000-000000000000'::uuid),
                      coalesce(?, '00000000-0000-0000-0000-000000000000'::uuid))
                order by id
                """, (rs, i) -> new Reply(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                    rs.getBoolean(4)), u.id(), u.id(), u.emailedUpTo());
            if (replies.isEmpty()) {
                continue;
            }
            Reply last = replies.getLast();
            String text = replies.stream()
                .map(r -> (r.senderName() != null ? r.senderName() : "The team") + ": " + (r.image() ? "(an image)" : r.body()))
                .reduce((a, b) -> a + "\n\n" + b).orElse("");
            // Only the team's own words go out: the customer's typed name and email aren't verified, so they
            // stay out of the text.
            if (mailer.send(List.of(u.email()), "New reply from " + u.company(), """
                Hi,

                You have a new reply in your chat with %s:

                %s

                Open the chat on our website to answer.
                """.formatted(u.company(), text))) {
                sent++;
                // A failed send is tried again on the next run.
                jdbc.update("update conversations set customer_emailed_message_id = ? where id = ?", last.id(), u.id());
            }
        }
        return sent;
    }

    private record Reply(UUID id, String senderName, String body, boolean image) {}
}
