package io.github.mesubash.quokra.common;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    // Ticks in microseconds, the precision PostgreSQL stores. A finer time (Linux clocks give nanoseconds) is
    // rounded by the database, so a value kept in memory, like a page cursor, wouldn't match the stored row.
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
