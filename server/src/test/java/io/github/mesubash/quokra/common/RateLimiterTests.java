package io.github.mesubash.quokra.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RateLimiterTests {

    // A clock the test can move forward.
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-29T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final TestClock clock = new TestClock();
    final RateLimiter limiter = new RateLimiter(clock);

    @Test
    void allowsUpToTheLimitThenRefusesWithARetryTime() {
        for (int i = 0; i < 3; i++) {
            limiter.check("a", 3);
        }
        clock.now = clock.now.plusSeconds(20);

        assertThatThrownBy(() -> limiter.check("a", 3))
            .isInstanceOfSatisfying(RateLimiter.RateLimitedException.class, e -> {
                assertThat(e.code()).isEqualTo("RATE_LIMITED");
                assertThat(e.retryAfterSeconds()).isEqualTo(40);
            });
    }

    @Test
    void keysAreCountedSeparately() {
        limiter.check("a", 1);

        limiter.check("b", 1);
    }

    @Test
    void aNewWindowStartsAfterAMinute() {
        limiter.check("a", 1);
        clock.now = clock.now.plus(Duration.ofMinutes(1));

        limiter.check("a", 1);
    }
}
