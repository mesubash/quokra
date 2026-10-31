package io.github.mesubash.quokra.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ClockConfigTests {

    @Test
    void timesAreInMicrosecondsAndMillisStillWork() {
        Clock fixed = Clock.fixed(Instant.parse("2026-10-31T10:00:00.123456789Z"), ZoneOffset.UTC);
        Clock clock = new ClockConfig.MicrosClock(fixed);

        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-10-31T10:00:00.123456Z"));
        assertThat(clock.millis()).isEqualTo(fixed.millis());
    }
}
