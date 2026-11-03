package io.github.mesubash.quokra.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

// Where a chat came from, recorded when it starts. page is a path only (no query, which can hold personal
// data), referrer only the other site's name, country the code Cloudflare sends. Nothing here is an IP address.
// siteVisitor: the website's own visitor id, for matching with its visitor logs; sites send it only with the
// visitor's consent for analytics.
@Embeddable
public record Visit(
    @Column(name = "visit_page") String page,
    @Column(name = "visit_referrer") String referrer,
    @Column(name = "visit_utm_source") String utmSource,
    @Column(name = "visit_utm_medium") String utmMedium,
    @Column(name = "visit_utm_campaign") String utmCampaign,
    @Column(name = "visit_country") String country,
    @Column(name = "visit_device") String device,
    @Column(name = "visit_language") String language,
    @Column(name = "visit_time_zone") String timeZone,
    @Column(name = "visit_site_visitor") String siteVisitor) {

    public static final Visit NONE = new Visit(null, null, null, null, null, null, null, null, null, null);
    private static final Pattern SITE_VISITOR = Pattern.compile("[A-Za-z0-9_.:-]{1,100}");

    private static final Set<String> DEVICES = Set.of("mobile", "tablet", "desktop");
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");
    // Cloudflare's codes for "unknown" and "Tor".
    private static final Set<String> NO_COUNTRY = Set.of("XX", "T1");
    private static final Pattern HOST = Pattern.compile("[a-z0-9.-]{1,100}");
    private static final Pattern TIME_ZONE = Pattern.compile("[A-Za-z_]+(/[A-Za-z0-9_+-]+){0,2}");
    private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,2}");

    // What the browser says, kept only when it looks like what it claims to be.
    public static Visit of(String page, String referrer, String utmSource, String utmMedium, String utmCampaign,
                           String country, String device, String language, String timeZone, String siteVisitor) {
        String code = country == null ? null : country.strip().toUpperCase(Locale.ROOT);
        return new Visit(
            path(page),
            referrer == null || !HOST.matcher(referrer.strip().toLowerCase(Locale.ROOT)).matches() ? null
                : referrer.strip().toLowerCase(Locale.ROOT),
            tag(utmSource), tag(utmMedium), tag(utmCampaign),
            code != null && COUNTRY.matcher(code).matches() && !NO_COUNTRY.contains(code) ? code : null,
            device != null && DEVICES.contains(device) ? device : null,
            language != null && LANGUAGE.matcher(language).matches() ? language : null,
            timeZone != null && timeZone.length() <= 64 && TIME_ZONE.matcher(timeZone).matches() ? timeZone : null,
            siteVisitor != null && SITE_VISITOR.matcher(siteVisitor).matches() ? siteVisitor : null);
    }

    private static String path(String page) {
        if (page == null || !page.startsWith("/")) {
            return null;
        }
        String path = page.split("[?#]", 2)[0];
        return path.length() > 300 ? path.substring(0, 300) : path;
    }

    private static String tag(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String clean = value.strip();
        return clean.length() > 100 ? clean.substring(0, 100) : clean;
    }
}
