package io.github.mesubash.quokra.mail;

import io.github.mesubash.quokra.common.ApiException;
import io.github.mesubash.quokra.common.RateLimiter;
import io.github.mesubash.quokra.conversation.Conversation;
import io.github.mesubash.quokra.conversation.ConversationService;
import io.github.mesubash.quokra.conversation.Message;
import io.github.mesubash.quokra.tenant.Tenant;
import io.github.mesubash.quokra.tenant.TenantRepository;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

// "Email me this chat": the conversation's messages, to the email the customer gave in it. Notes live in their
// own table, so they can't end up here.
@Service
public class Transcripts {

    static final int PAGE = 200;
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    private final ConversationService conversations;
    private final TenantRepository tenants;
    private final Mailer mailer;
    private final MailCheck check;
    private final RateLimiter rateLimiter;

    public Transcripts(ConversationService conversations, TenantRepository tenants, Mailer mailer, MailCheck check,
                       RateLimiter rateLimiter) {
        this.conversations = conversations;
        this.tenants = tenants;
        this.mailer = mailer;
        this.check = check;
        this.rateLimiter = rateLimiter;
    }

    // Mail is set up. Deliberately not the live server check: the public chat config asks this on every page
    // load and mustn't wait on SMTP; a send that fails anyway answers 503 EMAIL_FAILED.
    public boolean available() {
        return check.configured();
    }

    // Returns the address it went to, lightly masked for showing back.
    public String send(Conversation conversation, String ip) {
        if (!available()) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_OFF", "Email isn't available");
        }
        String to = conversation.getCustomerEmail();
        if (to == null || to.isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_CUSTOMER_EMAIL", "No email was given in this chat");
        }
        rateLimiter.check("transcript:" + conversation.getId(), RateLimiter.TRANSCRIPTS_PER_HOUR, Duration.ofHours(1));
        rateLimiter.check("transcript-ip:" + ip, RateLimiter.TRANSCRIPTS_PER_HOUR_PER_IP, Duration.ofHours(1));

        Tenant tenant = tenants.findById(conversation.getTenantId()).orElseThrow();
        String company = tenant.getDisplayName();
        ZoneId zone = zone(tenant);
        String date = DATE.format(conversation.getCreatedAt().atZone(zone));
        String text = "Hi,\n\nHere is your chat with %s on %s (times are %s).\n\n%s\n".formatted(
            company, date, zone.getId(), lines(conversation.getId(), company, zone));
        if (!mailer.send(List.of(to), "Your chat with " + company + " on " + date, text)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_FAILED", "The email couldn't be sent");
        }
        return mask(to);
    }

    private String lines(UUID conversationId, String company, ZoneId zone) {
        List<String> lines = new ArrayList<>();
        // The zero id sorts before every message, so paging starts at the first one.
        UUID after = new UUID(0, 0);
        List<Message> page;
        do {
            page = conversations.messages(conversationId, after, PAGE);
            for (Message m : page) {
                String body = m.getAttachmentKey() != null ? "[image]" : m.getBody();
                lines.add(switch (m.getSenderType()) {
                    case SYSTEM -> body;
                    // The customer's typed name isn't verified, so it stays out, as in the other emails.
                    case CUSTOMER -> line("You", m, body, zone);
                    case AGENT -> line(m.getSenderName() != null ? m.getSenderName() : company, m, body, zone);
                    case BOT -> line(company, m, body, zone);
                });
            }
            after = page.isEmpty() ? after : page.getLast().getId();
        } while (page.size() == PAGE);
        return String.join("\n", lines);
    }

    private static String line(String name, Message m, String body, ZoneId zone) {
        return name + " (" + TIME.format(m.getCreatedAt().atZone(zone)) + "): " + body;
    }

    private static ZoneId zone(Tenant tenant) {
        try {
            return tenant.getOpeningHours() != null ? ZoneId.of(tenant.getOpeningHours().timezone()) : ZoneOffset.UTC;
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }

    // pat@example.test -> p…@example.test
    static String mask(String email) {
        int at = email.indexOf('@');
        return at < 1 ? "…" : email.charAt(0) + "…" + email.substring(at);
    }
}
