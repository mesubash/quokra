package io.github.mesubash.quokra.attachment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

// Whether the file store works: writes and reads back a tiny file, at most every few minutes.
// Images are only offered while it does. Also shown in /actuator/health as "storage".
@Slf4j
@Component("storage")
public class StorageCheck implements HealthIndicator {

    static final Duration EVERY = Duration.ofMinutes(5);
    private static final byte[] PROBE = {'o', 'k'};

    public record Status(boolean ok, String store, String problem) {}

    private final FileStore files;
    private final String store;
    private final Clock clock;
    private Status last;
    private Instant checkedAt = Instant.MIN;

    public StorageCheck(FileStore files, @Value("${quokra.attachments.store:disk}") String store, Clock clock) {
        this.files = files;
        this.store = store;
        this.clock = clock;
    }

    public synchronized Status status() {
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
            files.put("_health/probe", PROBE, "text/plain");
            if (!Arrays.equals(files.get("_health/probe"), PROBE)) {
                return new Status(false, store, "The file read back was different");
            }
            return new Status(true, store, null);
        } catch (RuntimeException e) {
            log.warn("Attachment storage ({}) isn't working", store, e);
            return new Status(false, store, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    public Health health() {
        Status s = status();
        return (s.ok() ? Health.up() : Health.down().withDetail("problem", s.problem()))
            .withDetail("store", s.store()).build();
    }
}
