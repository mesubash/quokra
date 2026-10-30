package io.github.mesubash.quokra.admin;

import io.github.mesubash.quokra.admin.TenantAdmin.TenantForm;
import io.github.mesubash.quokra.admin.TenantAdmin.TenantView;
import io.github.mesubash.quokra.bot.BotConfigRegistry;
import io.github.mesubash.quokra.common.ApiException;
import io.github.mesubash.quokra.tenant.TenantAgents;
import io.github.mesubash.quokra.tenant.TenantSecrets;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
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

    record AgentsResponse(List<TenantAgents.Agent> agents, List<TenantAgents.Invite> invites) {}

    record AddAgentRequest(@NotBlank @Email @Size(max = 254) String email) {}

    record AddAgentResponse(TenantAgents.Added result) {}

    private final TenantAdmin tenants;
    private final TenantAgents agents;
    private final TenantSecrets secrets;
    private final BotConfigRegistry bots;

    public AdminController(TenantAdmin tenants, TenantAgents agents, TenantSecrets secrets, BotConfigRegistry bots) {
        this.tenants = tenants;
        this.agents = agents;
        this.secrets = secrets;
        this.bots = bots;
    }

    @GetMapping("/bots")
    public Items<String> bots() {
        return new Items<>(bots.names());
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
        return new AgentsResponse(agents.agents(tenant.id()), agents.invites(tenant.id()));
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
}
