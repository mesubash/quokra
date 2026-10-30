package io.github.mesubash.quokra.conversation;

import org.springframework.boot.context.properties.ConfigurationProperties;

// What companies may choose for keeping chats, set by whoever runs the server.
// maxMonths 0 means "forever" is allowed; otherwise chats go after maxMonths at the latest.
@ConfigurationProperties("quokra.retention")
public record RetentionLimits(Integer minMonths, Integer maxMonths) {

    public static final int DEFAULT_MONTHS = 12;

    public RetentionLimits {
        minMonths = minMonths == null || minMonths < 1 ? 1 : minMonths;
        maxMonths = maxMonths == null || maxMonths < 0 ? 60 : maxMonths;
        if (maxMonths > 0 && maxMonths < minMonths) {
            maxMonths = minMonths;
        }
    }

    public boolean foreverAllowed() {
        return maxMonths == 0;
    }

    public boolean allows(int months) {
        return months == 0 ? foreverAllowed() : months >= minMonths && (foreverAllowed() || months <= maxMonths);
    }

    // What actually applies to a company's choice, if the limits changed after they chose (0 = forever).
    public int effective(int months) {
        if (months == 0) {
            return foreverAllowed() ? 0 : maxMonths;
        }
        int atLeast = Math.max(months, minMonths);
        return foreverAllowed() ? atLeast : Math.min(atLeast, maxMonths);
    }
}
