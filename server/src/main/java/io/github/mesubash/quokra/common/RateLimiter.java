package io.github.mesubash.quokra.common;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

// Counts requests per key in fixed windows (one minute unless given) and refuses the ones over the limit.
// ponytail: in memory, per instance; move it to Redis when there's more than one instance.
@Component
public class RateLimiter {

    // Typed messages, per chat session.
    public static final int MESSAGES_PER_MINUTE = 20;
    // Taps on the bot's buttons, per chat session: kept apart, so clicking through the topics never blocks typing.
    public static final int OPTIONS_PER_MINUTE = 60;
    // Clients send "typing" at most every 3 seconds; this leaves room for a couple of tabs.
    public static final int TYPING_PER_MINUTE = 40;
    // Image uploads, per IP address (customers can open several sessions) and per agent.
    public static final int UPLOADS_PER_MINUTE = 20;
    // AI suggested replies, per agent.
    public static final int SUGGESTIONS_PER_MINUTE = 20;
    public static final int SESSIONS_PER_MINUTE = 5;
    // Chat transcripts by email, per conversation and per IP address.
    public static final int TRANSCRIPTS_PER_HOUR = 3;
    public static final int TRANSCRIPTS_PER_HOUR_PER_IP = 10;

    static final Duration WINDOW = Duration.ofMinutes(1);
    // Old windows are dropped once the map grows past this, so it can't grow without limit.
    static final int CLEANUP_AT = 10_000;

    private record Window(long start, long length, int count) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    public void check(String key, int limit) {
        check(key, limit, WINDOW);
    }

    public void check(String key, int limit, Duration length) {
        long now = clock.millis();
        long windowMs = length.toMillis();
        Window window = windows.compute(key, (k, w) -> w == null || now - w.start() >= w.length()
            ? new Window(now, windowMs, 1) : new Window(w.start(), w.length(), w.count() + 1));
        if (windows.size() > CLEANUP_AT) {
            windows.values().removeIf(w -> now - w.start() >= w.length());
        }
        if (window.count() > limit) {
            long retryAfter = Math.max(1, (window.start() + window.length() - now + 999) / 1000);
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
