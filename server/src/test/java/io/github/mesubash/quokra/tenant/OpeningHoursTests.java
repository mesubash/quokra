package io.github.mesubash.quokra.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpeningHoursTests {

    // Weekdays 9 to 5 in Berlin (UTC+1 in winter).
    static final OpeningHours WEEKDAYS = new OpeningHours("Europe/Berlin", Map.of(
        "MON", "09:00-17:00", "TUE", "09:00-17:00", "WED", "09:00-17:00", "THU", "09:00-17:00",
        "FRI", "09:00-17:00")).validated();

    @Test
    void openInsideTheHoursOnly() {
        assertThat(WEEKDAYS.isOpen(Instant.parse("2026-11-02T08:00:00Z"))).isTrue();   // Mon 09:00
        assertThat(WEEKDAYS.isOpen(Instant.parse("2026-11-02T07:59:00Z"))).isFalse();  // Mon 08:59
        assertThat(WEEKDAYS.isOpen(Instant.parse("2026-11-02T16:00:00Z"))).isFalse();  // Mon 17:00
        assertThat(WEEKDAYS.isOpen(Instant.parse("2026-10-31T12:00:00Z"))).isFalse();  // Saturday
    }

    @Test
    void saysWhenTheTeamIsBack() {
        assertThat(WEEKDAYS.nextOpening(Instant.parse("2026-11-02T06:00:00Z")))
            .hasValue("today at 09:00 (Europe/Berlin)");
        assertThat(WEEKDAYS.nextOpening(Instant.parse("2026-11-02T18:00:00Z")))
            .hasValue("tomorrow at 09:00 (Europe/Berlin)");
        assertThat(WEEKDAYS.nextOpening(Instant.parse("2026-10-30T18:00:00Z")))
            .hasValue("Monday at 09:00 (Europe/Berlin)");
        assertThat(WEEKDAYS.nextOpening(Instant.parse("2026-11-02T10:00:00Z"))).isEmpty();
    }

    @Test
    void rejectsWhatDoesntMakeSense() {
        assertThatThrownBy(() -> new OpeningHours("Mars/Base", Map.of("MON", "09:00-17:00")).validated())
            .hasMessageContaining("time zone");
        assertThatThrownBy(() -> new OpeningHours("UTC", Map.of("MON", "17:00-09:00")).validated())
            .hasMessageContaining("09:00-17:00");
        assertThatThrownBy(() -> new OpeningHours("UTC", Map.of("MONDAY", "09:00-17:00")).validated())
            .hasMessageContaining("MON, TUE");
        assertThatThrownBy(() -> new OpeningHours("UTC", Map.of()).validated())
            .hasMessageContaining("at least one day");
    }

    @Test
    void tidiesUpTheValues() {
        var hours = new OpeningHours(" UTC ", Map.of("MON", " 09:00 - 17:30 ", "TUE", "")).validated();
        assertThat(hours.timezone()).isEqualTo("UTC");
        assertThat(hours.days()).containsExactly(Map.entry("MON", "09:00-17:30"));
    }
}
