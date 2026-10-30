package io.github.mesubash.quokra.mail;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

// Whether Quokra can send email: SMTP_HOST and QUOKRA_MAIL_FROM are set, and the server answers (checked at
// most every 10 minutes). Emails only go out while this says ok. Shown in Settings and /actuator/health.
@Slf4j
@Component("email")
public class MailCheck implements HealthIndicator {

    static final Duration EVERY = Duration.ofMinutes(10);

    // configured: the settings are there; ok: and the mail server answered.
    public record Status(boolean configured, boolean ok, String problem) {}

    private final ObjectProvider<JavaMailSender> sender;
    private final String host;
    private final String from;
    private final Clock clock;
    private Status last;
    private Instant checkedAt = Instant.MIN;

    public MailCheck(ObjectProvider<JavaMailSender> sender, @Value("${spring.mail.host:}") String host,
                     @Value("${quokra.mail.from:}") String from, Clock clock) {
        this.sender = sender;
        this.host = host;
        this.from = from;
        this.clock = clock;
    }

    public boolean configured() {
        return !host.isBlank() && !from.isBlank() && sender.getIfAvailable() != null;
    }

    public synchronized Status status() {
        if (!configured()) {
            return new Status(false, false, null);
        }
        Instant now = clock.instant();
        if (last == null || checkedAt.plus(EVERY).isBefore(now)) {
            last = probe();
            checkedAt = now;
        }
        return last;
    }

    public boolean ok() {
        return status().ok();
    }

    private Status probe() {
        try {
            if (sender.getIfAvailable() instanceof JavaMailSenderImpl impl) {
                impl.testConnection();
            }
            return new Status(true, true, null);
        } catch (Exception e) {
            log.warn("Email server isn't reachable", e);
            return new Status(true, false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    public Health health() {
        Status s = status();
        if (!s.configured()) {
            return Health.up().withDetail("configured", false).build();
        }
        return (s.ok() ? Health.up() : Health.down().withDetail("problem", s.problem())).build();
    }
}
