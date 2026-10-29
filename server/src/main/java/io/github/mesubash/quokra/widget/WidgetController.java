package io.github.mesubash.quokra.widget;

import io.github.mesubash.quokra.bot.BotConfig;
import io.github.mesubash.quokra.bot.BotConfigRegistry;
import io.github.mesubash.quokra.common.ApiException;
import io.github.mesubash.quokra.session.SessionTokens;
import io.github.mesubash.quokra.tenant.Tenant;
import io.github.mesubash.quokra.tenant.TenantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/widget")
public class WidgetController {

    public record TenantView(String slug, String displayName, Branding branding, String teaser) {
        public record Branding(String logoUrl, String primaryColor) {}
    }

    public record SessionRequest(@NotBlank String publishableKey) {}

    public record SessionResponse(String sessionToken, UUID visitorId, Instant expiresAt) {}

    private final TenantRepository tenants;
    private final SessionTokens sessionTokens;
    private final BotConfigRegistry bots;

    public WidgetController(TenantRepository tenants, SessionTokens sessionTokens, BotConfigRegistry bots) {
        this.tenants = tenants;
        this.sessionTokens = sessionTokens;
        this.bots = bots;
    }

    @GetMapping("/tenants/{publishableKey}")
    public TenantView tenant(@PathVariable String publishableKey) {
        Tenant tenant = findTenant(publishableKey);
        return new TenantView(tenant.getSlug(), tenant.getDisplayName(),
            new TenantView.Branding(tenant.getLogoUrl(), tenant.getPrimaryColor()),
            bots.find(tenant.getBotConfig()).map(BotConfig::teaser).orElse(null));
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse startSession(@Valid @RequestBody SessionRequest request) {
        Tenant tenant = findTenant(request.publishableKey());
        SessionTokens.Issued issued = sessionTokens.issue(tenant.getId());
        return new SessionResponse(issued.token(), issued.session().visitorId(), issued.session().expiresAt());
    }

    private Tenant findTenant(String publishableKey) {
        return tenants.findByPublishableKey(publishableKey)
            .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Unknown publishable key"));
    }
}
