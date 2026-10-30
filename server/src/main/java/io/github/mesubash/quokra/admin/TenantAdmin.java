package io.github.mesubash.quokra.admin;

import io.github.mesubash.quokra.bot.BotConfigRegistry;
import io.github.mesubash.quokra.common.ApiException;
import io.github.mesubash.quokra.tenant.OpeningHours;
import io.github.mesubash.quokra.tenant.OpeningHoursConverter;
import io.github.mesubash.quokra.tenant.TenantRepository;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Creating and editing tenants (the companies that use Quokra). Only admins reach this.
@Service
public class TenantAdmin {

    public record TenantView(UUID id, String slug, String displayName, String publishableKey,
                             List<String> widgetOrigins, List<String> consoleOrigins, String logoUrl,
                             String primaryColor, String botConfig, int autoResolveMinutes,
                             OpeningHours openingHours, boolean hasSecret, int agentCount, int inviteCount,
                             Instant createdAt) {}

    // Everything an admin can set. The slug is only used when creating; it can't change later.
    // autoResolveMinutes: close quiet chats after this long (0 = never); missing means the default, 24 hours.
    // openingHours: null means always open.
    public record TenantForm(String slug, String displayName, List<String> widgetOrigins, List<String> consoleOrigins,
                             String logoUrl, String primaryColor, String botConfig, Integer autoResolveMinutes,
                             OpeningHours openingHours) {}

    static final Pattern SLUG = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?");
    static final Pattern ORIGIN = Pattern.compile("https?://[A-Za-z0-9.-]+(:\\d{1,5})?");
    static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    static final int MAX_ORIGINS = 20;
    static final int DEFAULT_AUTO_RESOLVE_MINUTES = 24 * 60;
    static final int MAX_AUTO_RESOLVE_MINUTES = 30 * 24 * 60;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final OpeningHoursConverter HOURS = new OpeningHoursConverter();

    private static final String SELECT = """
        select t.id, t.slug, t.display_name, t.publishable_key, t.widget_origins, t.console_origins, t.logo_url,
               t.primary_color, t.bot_config, t.auto_resolve_minutes, t.opening_hours, t.secret_key_enc is not null as has_secret,
               t.created_at,
               (select count(*) from tenant_agents a where a.tenant_id = t.id) as agents,
               (select count(*) from agent_invites i where i.tenant_id = t.id) as invites
        from tenants t
        """;

    private final JdbcTemplate jdbc;
    private final BotConfigRegistry bots;
    private final TenantRepository tenantRepository;

    public TenantAdmin(JdbcTemplate jdbc, BotConfigRegistry bots, TenantRepository tenantRepository) {
        this.jdbc = jdbc;
        this.tenantRepository = tenantRepository;
        this.bots = bots;
    }

    @Transactional(readOnly = true)
    public List<TenantView> list() {
        return jdbc.query(SELECT + " order by lower(t.display_name)", TenantAdmin::view);
    }

    @Transactional(readOnly = true)
    public TenantView get(String slug) {
        return find(slug).orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "No company with that slug"));
    }

    public Optional<TenantView> find(String slug) {
        return jdbc.query(SELECT + " where t.slug = ?", TenantAdmin::view, slug).stream().findFirst();
    }

    @Transactional
    public TenantView create(TenantForm form) {
        String slug = form.slug() == null ? "" : form.slug().strip();
        if (!SLUG.matcher(slug).matches()) {
            throw invalid("slug", "Use 2 to 40 lowercase letters, digits or dashes, e.g. acme-shop");
        }
        Clean c = clean(form);
        try {
            jdbc.update("""
                insert into tenants (id, slug, display_name, publishable_key, widget_origins, console_origins,
                                     logo_url, primary_color, bot_config, auto_resolve_minutes, opening_hours)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), slug, c.displayName(), newPublishableKey(), array(c.widgetOrigins()),
                array(c.consoleOrigins()), c.logoUrl(), c.primaryColor(), c.botConfig(), c.autoResolveMinutes(),
                c.openingHours());
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "SLUG_TAKEN", "That slug is already used");
        }
        return get(slug);
    }

    @Transactional
    public TenantView update(String slug, TenantForm form) {
        get(slug);
        Clean c = clean(form);
        jdbc.update("""
            update tenants set display_name = ?, widget_origins = ?, console_origins = ?, logo_url = ?,
                               primary_color = ?, bot_config = ?, auto_resolve_minutes = ?, opening_hours = ?
            where slug = ?
            """, c.displayName(), array(c.widgetOrigins()), array(c.consoleOrigins()), c.logoUrl(), c.primaryColor(),
            c.botConfig(), c.autoResolveMinutes(), c.openingHours(), slug);
        return get(slug);
    }

    // The tenant's own bot as JSON, or null to go back to its bot file.
    @Transactional
    public void setBot(String slug, String json) {
        tenantRepository.findBySlug(slug)
            .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "No company with that slug"))
            .useBot(json);
    }

    private record Clean(String displayName, List<String> widgetOrigins, List<String> consoleOrigins, String logoUrl,
                         String primaryColor, String botConfig, int autoResolveMinutes, String openingHours) {}

    private Clean clean(TenantForm form) {
        String name = form.displayName() == null ? "" : form.displayName().strip();
        if (name.isEmpty() || name.length() > 80) {
            throw invalid("displayName", "Give the company a name of up to 80 characters");
        }
        String color = blankToNull(form.primaryColor());
        if (color != null && !COLOR.matcher(color).matches()) {
            throw invalid("primaryColor", "Use a colour like #0051fb");
        }
        String logo = blankToNull(form.logoUrl());
        if (logo != null && (logo.length() > 500 || !(logo.startsWith("https://") || logo.startsWith("http://localhost")))) {
            throw invalid("logoUrl", "Use an https:// address for the logo");
        }
        String bot = form.botConfig() == null ? "" : form.botConfig().strip();
        if (bots.find(bot).isEmpty()) {
            throw invalid("botConfig", "Pick one of the bot files: " + String.join(", ", bots.names()));
        }
        int autoResolve = form.autoResolveMinutes() == null ? DEFAULT_AUTO_RESOLVE_MINUTES : form.autoResolveMinutes();
        if (autoResolve < 0 || autoResolve > MAX_AUTO_RESOLVE_MINUTES) {
            throw invalid("autoResolveMinutes", "Use 0 (never) up to 30 days, in minutes");
        }
        String hours;
        try {
            hours = form.openingHours() == null ? null : HOURS.convertToDatabaseColumn(form.openingHours().validated());
        } catch (IllegalArgumentException e) {
            throw invalid("openingHours", e.getMessage());
        }
        return new Clean(name, origins(form.widgetOrigins(), "widgetOrigins"),
            origins(form.consoleOrigins(), "consoleOrigins"), logo, color, bot, autoResolve, hours);
    }

    // Plain origins only (scheme, host, optional port), no paths; trailing slashes are forgiven.
    private static List<String> origins(List<String> values, String field) {
        var out = new LinkedHashSet<String>();
        for (String value : values == null ? List.<String>of() : values) {
            String origin = value == null ? "" : value.strip().replaceAll("/+$", "");
            if (origin.isEmpty()) {
                continue;
            }
            if (!ORIGIN.matcher(origin).matches()) {
                throw invalid(field, "'" + value + "' isn't a site address like https://www.example.com");
            }
            out.add(origin.toLowerCase());
        }
        if (out.size() > MAX_ORIGINS) {
            throw invalid(field, "At most " + MAX_ORIGINS + " sites");
        }
        return List.copyOf(out);
    }

    static String newPublishableKey() {
        byte[] random = new byte[18];
        RANDOM.nextBytes(random);
        return "pk_live_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private Array array(List<String> values) {
        return jdbc.execute((ConnectionCallback<Array>) c -> c.createArrayOf("text", values.toArray()));
    }

    private static TenantView view(ResultSet rs, int i) throws SQLException {
        return new TenantView(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("display_name"),
            rs.getString("publishable_key"), strings(rs.getArray("widget_origins")),
            strings(rs.getArray("console_origins")), rs.getString("logo_url"), rs.getString("primary_color"),
            rs.getString("bot_config"), rs.getInt("auto_resolve_minutes"),
            HOURS.convertToEntityAttribute(rs.getString("opening_hours")), rs.getBoolean("has_secret"),
            rs.getInt("agents"), rs.getInt("invites"),
            rs.getTimestamp("created_at").toInstant());
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + ": " + message);
    }
}
