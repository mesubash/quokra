package io.github.mesubash.quokra.admin;

import io.github.mesubash.quokra.admin.TenantAdmin.TenantForm;
import io.github.mesubash.quokra.admin.TenantAdmin.TenantView;
import io.github.mesubash.quokra.attachment.StorageCheck;
import io.github.mesubash.quokra.bot.BotConfigRegistry;
import io.github.mesubash.quokra.presence.AgentPresence;
import io.github.mesubash.quokra.bot.TenantBots;
import io.github.mesubash.quokra.canned.CannedReplies;
import io.github.mesubash.quokra.common.ApiException;
import io.github.mesubash.quokra.conversation.Message;
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

// Admin endpoints: set up the companies that use Quokra. Needs the admin role (see SecurityConfig).
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    record Items<T>(List<T> items) {}

    record SecretResponse(String secretKey) {}

    record AgentView(String sub, String name, String email, boolean online) {}

    record AgentsResponse(List<AgentView> agents, List<TenantAgents.Invite> invites) {}

    record AddAgentRequest(@NotBlank @Email @Size(max = 254) String email) {}

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

    public AdminController(TenantAdmin tenants, TenantAgents agents, TenantSecrets secrets, BotConfigRegistry bots,
                           CannedReplies replies, TenantBots tenantBots, TenantRepository tenantRepository,
                           StorageCheck storage, AgentPresence presence) {
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

    @GetMapping("/tenants")
    public Items<TenantView> list() {
        return new Items<>(tenants.list());
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
            .map(a -> new AgentView(a.sub(), a.name(), a.email(), online.contains(a.sub()))).toList(),
            agents.invites(tenant.id()));
    }

    @PostMapping("/tenants/{slug}/agents")
    public AddAgentResponse addAgent(@AuthenticationPrincipal Jwt jwt, @PathVariable String slug,
                                     @Valid @RequestBody AddAgentRequest request) {
        var tenant = tenants.get(slug);
        return new AddAgentResponse(agents.addByEmail(tenant.id(), request.email().strip(), jwt.getSubject()));
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
