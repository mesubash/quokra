package io.github.mesubash.quokra.ai;

import io.github.mesubash.quokra.common.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// The cost limits on AI calls (PLAN.md decision 10). Reaching one is never an error: the caller carries on
// without AI (fallback and handoff, or no suggestion). Logs say which limit, never what was asked.
@Slf4j
@Component
public class AiUsage {

    public enum Limit { PER_IP, PER_COMPANY, PER_SERVER }

    // Today's calls for a company, and its daily limit.
    public record Today(int calls, int perDay) {}

    private final JdbcTemplate jdbc;
    private final RateLimiter limiter;
    private final Clock clock;
    private final int perChat;
    private final int perIpHour;
    private final int perCompanyDay;
    private final int perServerDay;

    public AiUsage(JdbcTemplate jdbc, RateLimiter limiter, Clock clock,
                   @Value("${quokra.ai.per-chat}") int perChat,
                   @Value("${quokra.ai.per-ip-hour}") int perIpHour,
                   @Value("${quokra.ai.per-company-day}") int perCompanyDay,
                   @Value("${quokra.ai.per-server-day}") int perServerDay) {
        this.jdbc = jdbc;
        this.limiter = limiter;
        this.clock = clock;
        this.perChat = perChat;
        this.perIpHour = perIpHour;
        this.perCompanyDay = perCompanyDay;
        this.perServerDay = perServerDay;
    }

    // AI answers a customer can get in one chat.
    public int perChat() {
        return perChat;
    }

    // Counts one AI call for the company, or returns the limit that stops it. ip: the customer's, null for an
    // agent's suggestion (agents count towards the daily limits only).
    // ponytail: the server total is read, then the company row bumped, so a burst can pass the server limit
    // by a few calls; lock or keep a server row if it ever needs to be exact.
    public Limit use(UUID tenantId, String ip) {
        if (ip != null) {
            try {
                limiter.check("ai:" + ip, perIpHour, Duration.ofHours(1));
            } catch (RateLimiter.RateLimitedException e) {
                return reached(Limit.PER_IP, tenantId);
            }
        }
        LocalDate day = today();
        Integer total = jdbc.queryForObject("select coalesce(sum(calls), 0) from ai_usage where day = ?",
            Integer.class, day);
        if (total >= perServerDay) {
            return reached(Limit.PER_SERVER, tenantId);
        }
        int counted = perCompanyDay <= 0 ? 0 : jdbc.update("""
            insert into ai_usage (tenant_id, day, calls) values (?, ?, 1)
            on conflict (tenant_id, day) do update set calls = ai_usage.calls + 1 where ai_usage.calls < ?
            """, tenantId, day, perCompanyDay);
        return counted == 0 ? reached(Limit.PER_COMPANY, tenantId) : null;
    }

    public Today today(UUID tenantId) {
        Integer calls = jdbc.queryForObject("select coalesce(sum(calls), 0) from ai_usage where tenant_id = ? and day = ?",
            Integer.class, tenantId, today());
        return new Today(calls, perCompanyDay);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private Limit reached(Limit limit, UUID tenantId) {
        log.info("AI limit reached ({}) for company {}", limit, tenantId);
        return limit;
    }
}
