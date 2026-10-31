package io.github.mesubash.quokra.admin;

import io.github.mesubash.quokra.admin.TenantAdmin.TenantForm;
import io.github.mesubash.quokra.admin.TenantAdmin.TenantView;
import io.github.mesubash.quokra.attachment.StorageCheck;
import io.github.mesubash.quokra.bot.BotConfigRegistry;
import io.github.mesubash.quokra.presence.AgentPresence;
import io.github.mesubash.quokra.bot.TenantBots;
import io.github.mesubash.quokra.canned.CannedReplies;
import io.github.mesubash.quokra.common.ApiException;
import io.github.mesubash.quokra.conversation.RetentionLimits;
import io.github.mesubash.quokra.mail.MailCheck;
import io.github.mesubash.quokra.conversation.Message;
import io.github.mesubash.quokra.security.TenantAccess;
import io.github.mesubash.quokra.tenant.Tenant;
import io.github.mesubash.quokra.tenant.TenantAgents;
import io.github.mesubash.quokra.tenant.TenantRepository;
import io.github.mesubash.quokra.tenant.TenantSecrets;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Admin endpoints: set up the companies that use Quokra. Server admins manage every company, company
// admins only their own (TenantAccess, checked in SecurityConfig).
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    record Items<T>(List<T> items) {}

    record SecretResponse(String secretKey) {}

    record AgentView(String sub, String name, String email, boolean admin, boolean online) {}

    record AgentsResponse(List<AgentView> agents, List<TenantAgents.Invite> invites) {}

    // admin: as company admin (missing: no).
    record AddAgentRequest(@NotBlank @Email @Size(max = 254) String email, Boolean admin) {}

    record AgentForm(boolean admin) {}

    record AddAgentResponse(TenantAgents.Added result) {}

    // custom: edited in the Admin tab; otherwise the bot file is used. bot has the same fields as a bot file.
    record BotResponse(boolean custom, String file, Map<String, Object> bot) {}

    record ReplyForm(@NotBlank @Size(max = CannedReplies.MAX_TITLE) String title,
                     @NotBlank @Size(max = Message.MAX_BODY_LENGTH) String body) {}

    private final TenantAdmin tenants;
    private final TenantAgents agents;
    private final TenantSecrets secrets;
    private final BotConfigRegistry bots;
    private final CannedReplies replies;
    private final TenantBots tenantBots;
    private final TenantRepository tenantRepository;
    private final StorageCheck storage;
    private final AgentPresence presence;
    private final RetentionLimits retention;
    private final MailCheck mail;
    private final TenantAccess access;

    public AdminController(TenantAdmin tenants, TenantAgents agents, TenantSecrets secrets, BotConfigRegistry bots,
                           CannedReplies replies, TenantBots tenantBots, TenantRepository tenantRepository,
                           StorageCheck storage, AgentPresence presence, RetentionLimits retention,
                           MailCheck mail, TenantAccess access) {
        this.access = access;
        this.mail = mail;
        this.retention = retention;
        this.storage = storage;
        this.presence = presence;
        this.tenantBots = tenantBots;
        this.tenantRepository = tenantRepository;
        this.replies = replies;
        this.tenants = tenants;
        this.agents = agents;
        this.secrets = secrets;
        this.bots = bots;
    }

    @GetMapping("/bots")
    public Items<String> bots() {
        return new Items<>(bots.names());
    }

    // Whether image storage works, for the "Allow images" setting.
    @GetMapping("/storage")
    public StorageCheck.Status storage() {
        return storage.status();
    }

    // Whether Quokra can send email (SMTP settings, and the server answering).
    @GetMapping("/mail")
    public MailCheck.Status mail() {
        return mail.status();
    }

    // What companies may choose for keeping chats (maxMonths 0: "forever" is allowed too).
    @GetMapping("/retention")
    public RetentionLimits retention() {
        return retention;
    }

    // Every company for server admins; for company admins, only theirs.
    @GetMapping("/tenants")
    public Items<TenantView> list(Authentication auth) {
        if (TenantAccess.serverAdmin(auth)) {
            return new Items<>(tenants.list());
        }
        var managed = access.managed(auth);
        return new Items<>(tenants.list().stream().filter(t -> managed.contains(t.slug())).toList());
    }

    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public TenantView create(@RequestBody TenantForm form) {
        return tenants.create(form);
    }

    @GetMapping("/tenants/{slug}")
    public TenantView get(@PathVariable String slug) {
        return tenants.get(slug);
    }

    @PutMapping("/tenants/{slug}")
    public TenantView update(@PathVariable String slug, @RequestBody TenantForm form) {
        return tenants.update(slug, form);
    }

    // A new secret key for identifying logged-in customers. Shown once; the old one stops working.
    @PostMapping("/tenants/{slug}/secret")
    public SecretResponse rotateSecret(@PathVariable String slug) {
        tenants.get(slug);
        if (!secrets.enabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "SECRETS_DISABLED",
                "Set TENANT_SECRET_ENCRYPTION_KEY on the server to use secret keys");
        }
        return new SecretResponse(secrets.rotate(slug));
    }

    @GetMapping("/tenants/{slug}/agents")
    public AgentsResponse agents(@PathVariable String slug) {
        var tenant = tenants.get(slug);
        List<TenantAgents.Agent> list = agents.agents(tenant.id());
        var online = presence.online(list.stream().map(TenantAgents.Agent::sub).toList());
        return new AgentsResponse(list.stream()
            .map(a -> new AgentView(a.sub(), a.name(), a.email(), a.admin(), online.contains(a.sub()))).toList(),
            agents.invites(tenant.id()));
    }

    @PostMapping("/tenants/{slug}/agents")
    public AddAgentResponse addAgent(@AuthenticationPrincipal Jwt jwt, @PathVariable String slug,
                                     @Valid @RequestBody AddAgentRequest request) {
        var tenant = tenants.get(slug);
        return new AddAgentResponse(agents.addByEmail(tenant.id(), request.email().strip(),
            Boolean.TRUE.equals(request.admin()), jwt.getSubject()));
    }

    // Makes an agent company admin, or a plain agent again.
    @PutMapping("/tenants/{slug}/agents/{agentSub}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateAgent(@PathVariable String slug, @PathVariable String agentSub, @RequestBody AgentForm form) {
        if (!agents.setAdmin(tenants.get(slug).id(), agentSub, form.admin())) {
            throw ApiException.notFound("AGENT_NOT_FOUND", "That agent isn't in this company");
        }
    }

    @DeleteMapping("/tenants/{slug}/agents/{agentSub}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeAgent(@PathVariable String slug, @PathVariable String agentSub) {
        if (!agents.remove(tenants.get(slug).id(), agentSub)) {
            throw ApiException.notFound("AGENT_NOT_FOUND", "That agent isn't in this company");
        }
    }

    @DeleteMapping("/tenants/{slug}/invites/{email}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeInvite(@PathVariable String slug, @PathVariable String email) {
        if (!agents.removeInvite(tenants.get(slug).id(), email)) {
            throw ApiException.notFound("INVITE_NOT_FOUND", "No invite for that email");
        }
    }

    @GetMapping("/tenants/{slug}/bot")
    public BotResponse bot(@PathVariable String slug) {
        return botOf(slug);
    }

    // Replaces the tenant's bot with an edited one, checked with the same rules as bot files.
    @PutMapping("/tenants/{slug}/bot")
    public BotResponse saveBot(@PathVariable String slug, @RequestBody Map<String, Object> bot) {
        String json;
        try {
            json = tenantBots.validate(bot);
        } catch (TenantBots.InvalidBot e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", e.getMessage());
        }
        tenants.setBot(slug, json);
        return botOf(slug);
    }

    // Back to the bot file; the edited bot is dropped.
    @DeleteMapping("/tenants/{slug}/bot")
    public BotResponse resetBot(@PathVariable String slug) {
        tenants.setBot(slug, null);
        return botOf(slug);
    }

    private BotResponse botOf(String slug) {
        Tenant tenant = tenantRepository.findBySlug(slug)
            .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "No company with that slug"));
        return new BotResponse(tenant.getBotCustom() != null, tenant.getBotConfig(),
            tenantBots.fieldsFor(tenant).orElse(null));
    }

    @GetMapping("/tenants/{slug}/replies")
    public Items<CannedReplies.Reply> replies(@PathVariable String slug) {
        return new Items<>(replies.list(tenants.get(slug).id()));
    }

    @PostMapping("/tenants/{slug}/replies")
    @ResponseStatus(HttpStatus.CREATED)
    public CannedReplies.Reply addReply(@PathVariable String slug, @Valid @RequestBody ReplyForm form) {
        return replies.add(tenants.get(slug).id(), form.title().strip(), form.body().strip());
    }

    @PutMapping("/tenants/{slug}/replies/{id}")
    public CannedReplies.Reply updateReply(@PathVariable String slug, @PathVariable UUID id,
                                           @Valid @RequestBody ReplyForm form) {
        String title = form.title().strip();
        String body = form.body().strip();
        if (!replies.update(tenants.get(slug).id(), id, title, body)) {
            throw replyNotFound();
        }
        return new CannedReplies.Reply(id, title, body);
    }

    @DeleteMapping("/tenants/{slug}/replies/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeReply(@PathVariable String slug, @PathVariable UUID id) {
        if (!replies.remove(tenants.get(slug).id(), id)) {
            throw replyNotFound();
        }
    }

    private static ApiException replyNotFound() {
        return ApiException.notFound("REPLY_NOT_FOUND", "No saved reply with that id");
    }
}
