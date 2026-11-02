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
// Closing at 24:00 (or 00:00) means midnight; closing before opening runs into the next day, so
// "22:00-02:00" on Friday is open until 02:00 on Saturday.
public record OpeningHours(String timezone, Map<String, String> days) {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final int DAY = 24 * 60;

    // Minutes after midnight; to is up to DAY, and less than from when it ends the next day.
    private record Range(int from, int to) {
        boolean overnight() {
            return to < from;
        }

        @Override
        public String toString() {
            return time(from) + "-" + time(to);
        }
    }

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
            clean.put(key(day), range(value).toString());
        }
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Open on at least one day, or turn opening hours off");
        }
        return new OpeningHours(zone.getId(), Map.copyOf(clean));
    }

    public boolean isOpen(Instant at) {
        ZonedDateTime local = at.atZone(ZoneId.of(timezone));
        int now = minutes(local.toLocalTime());
        boolean today = rangeOn(local.getDayOfWeek())
            .map(r -> now >= r.from() && (r.overnight() || now < r.to()))
            .orElse(false);
        // Still open from yesterday's overnight hours.
        boolean fromYesterday = rangeOn(local.getDayOfWeek().minus(1))
            .map(r -> r.overnight() && now < r.to())
            .orElse(false);
        return today || fromYesterday;
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
            if (r.isEmpty() || (ahead == 0 && minutes(local.toLocalTime()) >= r.get().from())) {
                continue;
            }
            String when = switch (ahead) {
                case 0 -> "today";
                case 1 -> "tomorrow";
                default -> date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            };
            return Optional.of(when + " at " + time(r.get().from()) + " (" + timezone + ")");
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
                int from = minutes(LocalTime.parse(parts[0]));
                // 24:00 isn't a LocalTime; 00:00 as closing time means the same.
                int to = parts[1].equals("24:00") ? DAY : minutes(LocalTime.parse(parts[1]));
                if (to == 0) {
                    to = DAY;
                }
                if (from != to) {
                    return new Range(from, to);
                }
            }
        } catch (DateTimeParseException ignored) {
            // falls through to the message below
        }
        throw new IllegalArgumentException("'" + value + "' isn't a range like 09:00-17:00 or 22:00-02:00");
    }

    private static int minutes(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    private static String time(int minutes) {
        return minutes == DAY ? "24:00" : TIME.format(LocalTime.of(minutes / 60, minutes % 60));
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
