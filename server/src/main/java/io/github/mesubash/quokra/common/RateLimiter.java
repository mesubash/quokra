package io.github.mesubash.quokra.common;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

// Counts requests per key in fixed one-minute windows and refuses the ones over the limit.
// ponytail: in memory, per instance; move it to Redis when there's more than one instance.
@Component
public class RateLimiter {

    public static final int MESSAGES_PER_MINUTE = 20;
    public static final int SESSIONS_PER_MINUTE = 5;

    static final Duration WINDOW = Duration.ofMinutes(1);
    // Old windows are dropped once the map grows past this, so it can't grow without limit.
    static final int CLEANUP_AT = 10_000;

    private record Window(long start, int count) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    public void check(String key, int limit) {
        long now = clock.millis();
        long windowMs = WINDOW.toMillis();
        Window window = windows.compute(key, (k, w) ->
            w == null || now - w.start() >= windowMs ? new Window(now, 1) : new Window(w.start(), w.count() + 1));
        if (windows.size() > CLEANUP_AT) {
            windows.values().removeIf(w -> now - w.start() >= windowMs);
        }
        if (window.count() > limit) {
            long retryAfter = Math.max(1, (window.start() + windowMs - now + 999) / 1000);
            throw new RateLimitedException(retryAfter);
        }
    }

    public static class RateLimitedException extends ApiException {

        private final long retryAfterSeconds;

        RateLimitedException(long retryAfterSeconds) {
            super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests, try again in a moment");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
