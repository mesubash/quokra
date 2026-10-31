package io.github.mesubash.quokra.common;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return new MicrosClock(Clock.systemUTC());
    }

    // Times in microseconds, the precision PostgreSQL stores. A finer time (Linux clocks give nanoseconds) is
    // rounded by the database, so a value kept in memory, like a page cursor, wouldn't match the stored row.
    // (Clock.tick can't do this: below a millisecond its millis() divides by zero.)
    static final class MicrosClock extends Clock {

        private final Clock base;

        MicrosClock(Clock base) {
            this.base = base;
        }

        @Override
        public ZoneId getZone() {
            return base.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MicrosClock(base.withZone(zone));
        }

        @Override
        public Instant instant() {
            return base.instant().truncatedTo(ChronoUnit.MICROS);
        }

        @Override
        public long millis() {
            return base.millis();
        }
    }
}
