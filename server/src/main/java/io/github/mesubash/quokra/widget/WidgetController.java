package io.github.mesubash.quokra.widget;

import io.github.mesubash.quokra.bot.BotConfig;
import io.github.mesubash.quokra.bot.TenantBots;
import io.github.mesubash.quokra.common.ApiException;
import io.github.mesubash.quokra.common.RateLimiter;
import io.github.mesubash.quokra.presence.AgentPresence;
import jakarta.servlet.http.HttpServletRequest;
import io.github.mesubash.quokra.session.SessionTokens;
import io.github.mesubash.quokra.tenant.Tenant;
import io.github.mesubash.quokra.tenant.TenantRepository;
import jakarta.validation.Valid;
import io.github.mesubash.quokra.conversation.ConversationService;
import io.github.mesubash.quokra.conversation.CustomerIdentity;
import io.github.mesubash.quokra.session.IdentityTokens;
import io.github.mesubash.quokra.session.SessionTokens.Session;
import io.github.mesubash.quokra.tenant.TenantSecrets;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.time.Clock;
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

    // open: inside the tenant's opening hours (always, when it has none). attachments: images can be sent.
    // teamOnline: someone of the team is answering chats right now.
    public record TenantView(String slug, String displayName, Branding branding, String teaser, Texts texts,
                             boolean open, boolean attachments, boolean teamOnline) {
        public record Branding(String logoUrl, String primaryColor) {}

        // The wording the chat panel shows itself; the bot's own lines stay on the server.
        public record Texts(String online, String waiting, String closed, String placeholder, String offline) {
            static Texts of(BotConfig.Texts t) {
                return new Texts(t.online(), t.waiting(), t.closed(), t.placeholder(), t.offline());
            }
        }
    }

    public record SessionRequest(@NotBlank String publishableKey) {}

    public record SessionResponse(String sessionToken, UUID visitorId, Instant expiresAt) {}

    public record IdentifyRequest(@NotBlank @Size(max = 4096) String identityToken) {}

    public record IdentifyResponse(String sessionToken, UUID visitorId, CustomerIdentity customer, Instant expiresAt) {}

    private final TenantRepository tenants;
    private final SessionTokens sessionTokens;
    private final TenantBots bots;
    private final TenantSecrets secrets;
    private final IdentityTokens identityTokens;
    private final ConversationService conversations;
    private final RateLimiter rateLimiter;
    private final Clock clock;
    private final AgentPresence presence;

    public WidgetController(TenantRepository tenants, SessionTokens sessionTokens, TenantBots bots,
                            TenantSecrets secrets, IdentityTokens identityTokens, ConversationService conversations,
                            RateLimiter rateLimiter, Clock clock, AgentPresence presence) {
        this.clock = clock;
        this.presence = presence;
        this.rateLimiter = rateLimiter;
        this.tenants = tenants;
        this.sessionTokens = sessionTokens;
        this.bots = bots;
        this.secrets = secrets;
        this.identityTokens = identityTokens;
        this.conversations = conversations;
    }

    @GetMapping("/tenants/{publishableKey}")
    public TenantView tenant(@PathVariable String publishableKey) {
        Tenant tenant = findTenant(publishableKey);
        var bot = bots.forTenant(tenant);
        return new TenantView(tenant.getSlug(), tenant.getDisplayName(),
            new TenantView.Branding(tenant.getLogoUrl(), tenant.getPrimaryColor()),
            bot.map(BotConfig::teaser).orElse(null),
            TenantView.Texts.of(bot.map(BotConfig::texts).orElse(BotConfig.Texts.DEFAULT)),
            tenant.isOpen(clock.instant()), conversations.imagesAllowed(tenant), presence.anyoneOnline(tenant.getId()));
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse startSession(@Valid @RequestBody SessionRequest request, HttpServletRequest http) {
        // Behind a proxy, the client address comes from X-Forwarded-For (server.forward-headers-strategy).
        rateLimiter.check("session:" + http.getRemoteAddr(), RateLimiter.SESSIONS_PER_MINUTE);
        Tenant tenant = findTenant(request.publishableKey());
        SessionTokens.Issued issued = sessionTokens.issue(tenant.getId());
        return new SessionResponse(issued.token(), issued.session().visitorId(), issued.session().expiresAt());
    }

    // The host's backend vouches for a logged-in customer. The token must be signed with this session's tenant
    // secret, so a token for one tenant can't be used on another.
    @PostMapping("/sessions/identify")
    public IdentifyResponse identify(@AuthenticationPrincipal Session session, @Valid @RequestBody IdentifyRequest request) {
        String secret = secrets.secretFor(session.tenantId())
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "IDENTITY_TOKEN_INVALID",
                "Identifying customers isn't set up for this site"));
        CustomerIdentity customer = identityTokens.verify(request.identityToken(), secret);
        UUID visitorId;
        try {
            visitorId = conversations.identify(session.tenantId(), session.visitorId(), customer);
        } catch (DataIntegrityViolationException e) {
            // Identified twice at the same moment; the other request did the merge.
            visitorId = session.visitorId();
        }
        SessionTokens.Issued issued = sessionTokens.issue(session.tenantId(), visitorId, customer);
        return new IdentifyResponse(issued.token(), visitorId, customer, issued.session().expiresAt());
    }

    private Tenant findTenant(String publishableKey) {
        return tenants.findByPublishableKey(publishableKey)
            .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Unknown publishable key"));
    }
}
