package io.github.mesubash.quokra.tenant;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

// When a tenant's team answers chats. days maps MON..SUN to "09:00-17:00"; days left out are closed.
public record OpeningHours(String timezone, Map<String, String> days) {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private record Range(LocalTime from, LocalTime to) {}

    // Throws IllegalArgumentException with a message for the admin when something doesn't make sense.
    public OpeningHours validated() {
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone == null ? "" : timezone.strip());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("'" + timezone + "' isn't a time zone like Europe/Berlin");
        }
        if (days != null && !days.keySet().stream().allMatch(OpeningHours::isDay)) {
            throw new IllegalArgumentException("Days are MON, TUE, WED, THU, FRI, SAT and SUN");
        }
        var clean = new LinkedHashMap<String, String>();
        for (DayOfWeek day : DayOfWeek.values()) {
            String value = days == null ? null : days.get(key(day));
            if (value == null || value.isBlank()) {
                continue;
            }
            Range r = range(value);
            clean.put(key(day), TIME.format(r.from()) + "-" + TIME.format(r.to()));
        }
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Open on at least one day, or turn opening hours off");
        }
        return new OpeningHours(zone.getId(), Map.copyOf(clean));
    }

    public boolean isOpen(Instant at) {
        ZonedDateTime local = at.atZone(ZoneId.of(timezone));
        return rangeOn(local.getDayOfWeek())
            .map(r -> !local.toLocalTime().isBefore(r.from()) && local.toLocalTime().isBefore(r.to()))
            .orElse(false);
    }

    // When the team is next in, e.g. "tomorrow at 09:00 (Europe/Berlin)". Empty while open.
    public Optional<String> nextOpening(Instant at) {
        if (isOpen(at)) {
            return Optional.empty();
        }
        ZonedDateTime local = at.atZone(ZoneId.of(timezone));
        for (int ahead = 0; ahead <= 7; ahead++) {
            ZonedDateTime date = local.plusDays(ahead);
            Optional<Range> r = rangeOn(date.getDayOfWeek());
            if (r.isEmpty() || (ahead == 0 && !local.toLocalTime().isBefore(r.get().from()))) {
                continue;
            }
            String when = switch (ahead) {
                case 0 -> "today";
                case 1 -> "tomorrow";
                default -> date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            };
            return Optional.of(when + " at " + TIME.format(r.get().from()) + " (" + timezone + ")");
        }
        return Optional.empty();
    }

    private Optional<Range> rangeOn(DayOfWeek day) {
        return Optional.ofNullable(days.get(key(day))).map(OpeningHours::range);
    }

    private static Range range(String value) {
        String[] parts = value.strip().split("\\s*-\\s*");
        try {
            if (parts.length == 2) {
                Range r = new Range(LocalTime.parse(parts[0]), LocalTime.parse(parts[1]));
                if (r.from().isBefore(r.to())) {
                    return r;
                }
            }
        } catch (DateTimeParseException ignored) {
            // falls through to the message below
        }
        throw new IllegalArgumentException("'" + value + "' isn't a range like 09:00-17:00 (closing after opening)");
    }

    private static String key(DayOfWeek day) {
        return day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT);
    }

    private static boolean isDay(String key) {
        for (DayOfWeek day : DayOfWeek.values()) {
            if (key(day).equals(key)) {
                return true;
            }
        }
        return false;
    }
}
