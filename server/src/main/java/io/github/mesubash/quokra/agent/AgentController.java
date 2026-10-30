package io.github.mesubash.quokra.agent;

import io.github.mesubash.quokra.agent.AgentViews.ConversationView;
import io.github.mesubash.quokra.agent.AgentViews.EventView;
import io.github.mesubash.quokra.agent.AgentViews.Summary;
import io.github.mesubash.quokra.common.ApiException;
import io.github.mesubash.quokra.conversation.AgentRef;
import io.github.mesubash.quokra.conversation.Conversation;
import io.github.mesubash.quokra.conversation.ConversationService;
import io.github.mesubash.quokra.contact.Contact;
import io.github.mesubash.quokra.contact.ContactRepository;
import io.github.mesubash.quokra.conversation.MessageView;
import io.github.mesubash.quokra.conversation.NoteView;
import io.github.mesubash.quokra.conversation.PageCursor;
import io.github.mesubash.quokra.conversation.Status;
import io.github.mesubash.quokra.security.AgentJwtConverter;
import io.github.mesubash.quokra.tenant.Tenant;
import io.github.mesubash.quokra.tenant.TenantAgents;
import io.github.mesubash.quokra.tenant.TenantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Agent endpoints. An agent only sees tenants they're a member of; anything else looks like it doesn't exist.
@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 200;
    static final int HISTORY_LIMIT = 100;

    record Me(String sub, String name, List<String> tenants) {}

    record TenantItem(UUID id, String slug, String displayName) {}

    record Page<T>(List<T> items, String nextCursor) {}

    record Detail(ConversationView conversation, List<MessageView> messages, List<EventView> events,
                  List<NoteView> notes) {}

    record One(ConversationView conversation) {}

    record ReplyRequest(@Size(max = 64) String clientMessageId, String body) {}

    record ReplyResponse(MessageView message) {}

    record TransferRequest(@Size(max = 255) String agentSub) {}

    record NoteRequest(String body) {}

    record NoteResponse(NoteView note) {}

    record ReadRequest(@NotNull UUID messageId) {}

    private final ConversationService service;
    private final TenantRepository tenants;
    private final TenantAgents tenantAgents;
    private final ContactRepository contacts;
    private final AgentReads reads;
    private final AgentJwtConverter agentTokens;

    public AgentController(ConversationService service, TenantRepository tenants, TenantAgents tenantAgents,
                           ContactRepository contacts, AgentReads reads, AgentJwtConverter agentTokens) {
        this.reads = reads;
        this.agentTokens = agentTokens;
        this.service = service;
        this.tenants = tenants;
        this.tenantAgents = tenantAgents;
        this.contacts = contacts;
    }

    @GetMapping("/me")
    public Me me(@AuthenticationPrincipal Jwt jwt) {
        tenantAgents.rememberName(jwt.getSubject(), agentTokens.displayName(jwt));
        return new Me(jwt.getSubject(), agentTokens.displayName(jwt),
            myTenants(jwt).values().stream().map(Tenant::getSlug).sorted().toList());
    }

    @GetMapping("/tenants")
    public Page<TenantItem> tenants(@AuthenticationPrincipal Jwt jwt) {
        return new Page<>(myTenants(jwt).values().stream()
            .map(t -> new TenantItem(t.getId(), t.getSlug(), t.getDisplayName()))
            .sorted((a, b) -> a.slug().compareTo(b.slug()))
            .toList(), null);
    }

    @GetMapping("/conversations")
    public Page<Summary> conversations(@AuthenticationPrincipal Jwt jwt,
                                       @RequestParam Status status,
                                       @RequestParam(required = false) String tenant,
                                       @RequestParam(required = false) String assignee,
                                       @RequestParam(required = false) String cursor,
                                       @RequestParam(required = false) @Size(max = 100) String q,
                                       @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        if (status == Status.BOT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "status must be WAITING, HUMAN or RESOLVED");
        }
        Map<UUID, Tenant> mine = myTenants(jwt);
        List<UUID> ids = tenant == null ? List.copyOf(mine.keySet())
            : mine.values().stream().filter(t -> t.getSlug().equals(tenant)).map(Tenant::getId).toList();
        String assigneeSub = "me".equals(assignee) ? jwt.getSubject() : null;

        int size = Math.clamp(limit, 1, MAX_LIMIT);
        List<Conversation> page = service.list(ids, status, assigneeSub, q,
            cursor == null ? null : PageCursor.decode(cursor), size);
        // ponytail: one query per row for the preview; fine for a page of 50, batch it if lists grow.
        Map<UUID, Integer> unread = reads.unreadCounts(jwt.getSubject(), page.stream().map(Conversation::getId).toList());
        List<Summary> items = page.stream()
            .map(c -> Summary.of(c, mine.get(c.getTenantId()).getSlug(), service.lastMessage(c.getId()).orElse(null),
                unread.getOrDefault(c.getId(), 0)))
            .toList();
        String next = page.size() == size ? PageCursor.after(page.getLast()).encode() : null;
        return new Page<>(items, next);
    }

    @GetMapping("/conversations/counts")
    public ConversationService.Counts counts(@AuthenticationPrincipal Jwt jwt) {
        return service.counts(myTenants(jwt).keySet(), jwt.getSubject());
    }

    @GetMapping("/conversations/{id}")
    public Detail conversation(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Conversation c = visible(jwt, id);
        return new Detail(view(c),
            service.messages(id, null, HISTORY_LIMIT).stream().map(MessageView::of).toList(),
            service.events(id).stream().map(EventView::of).toList(),
            service.notes(id).stream().map(NoteView::of).toList());
    }

    @PostMapping("/conversations/{id}/accept")
    public One accept(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        visible(jwt, id);
        try {
            return new One(view(service.accept(id, agent(jwt))));
        } catch (ObjectOptimisticLockingFailureException e) {
            // Someone else accepted it between our read and our write.
            throw ConversationService.alreadyAccepted();
        }
    }

    @PostMapping("/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public ReplyResponse reply(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                               @Valid @RequestBody ReplyRequest request) {
        visible(jwt, id);
        return new ReplyResponse(MessageView.of(
            service.addAgentMessage(id, agent(jwt), request.clientMessageId(), request.body())));
    }

    @PostMapping("/conversations/{id}/resolve")
    public One resolve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        visible(jwt, id);
        return new One(view(service.resolve(id, agent(jwt))));
    }

    @PostMapping("/conversations/{id}/return-to-bot")
    public One returnToBot(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        visible(jwt, id);
        return new One(view(service.returnToBot(id, agent(jwt))));
    }

    // The agent has seen the conversation up to this message.
    @PostMapping("/conversations/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReadRequest request) {
        visible(jwt, id);
        reads.markRead(id, jwt.getSubject(), request.messageId());
    }

    @PostMapping("/conversations/{id}/reopen")
    public One reopen(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        visible(jwt, id);
        return new One(view(service.reopen(id, agent(jwt))));
    }

    // Who the conversation can be transferred to.
    @GetMapping("/conversations/{id}/agents")
    public Page<TenantAgents.Member> agents(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return new Page<>(tenantAgents.members(visible(jwt, id).getTenantId()), null);
    }

    @PostMapping("/conversations/{id}/transfer")
    public One transfer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                        @Valid @RequestBody TransferRequest request) {
        Conversation c = visible(jwt, id);
        AgentRef to = null;
        if (request.agentSub() != null) {
            if (request.agentSub().equals(jwt.getSubject())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "You already have this conversation");
            }
            to = tenantAgents.members(c.getTenantId()).stream()
                .filter(m -> m.sub().equals(request.agentSub()))
                .map(m -> new AgentRef(m.sub(), m.name()))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "That agent doesn't work on this tenant"));
        }
        return new One(view(service.transfer(id, agent(jwt), to)));
    }

    @PostMapping("/conversations/{id}/block")
    public One block(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        visible(jwt, id);
        return new One(view(service.block(id, agent(jwt))));
    }

    @PostMapping("/contacts/{contactId}/unblock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID contactId) {
        Contact contact = contacts.findById(contactId)
            .filter(c -> tenantAgents.tenantIdsFor(jwt.getSubject()).contains(c.getTenantId()))
            .orElseThrow(() -> ApiException.notFound("CONTACT_NOT_FOUND", "Contact not found"));
        service.unblock(contact.getId());
    }

    @PostMapping("/conversations/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public NoteResponse note(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                             @Valid @RequestBody NoteRequest request) {
        visible(jwt, id);
        return new NoteResponse(NoteView.of(service.addNote(id, agent(jwt), request.body())));
    }

    private Conversation visible(Jwt jwt, UUID id) {
        Conversation c;
        try {
            c = service.get(id);
        } catch (ApiException e) {
            throw notFound();
        }
        if (!tenantAgents.tenantIdsFor(jwt.getSubject()).contains(c.getTenantId())) {
            throw notFound();
        }
        return c;
    }

    private ConversationView view(Conversation c) {
        boolean blocked = c.getContactId() != null
            && contacts.findById(c.getContactId()).map(Contact::isBlocked).orElse(false);
        return ConversationView.of(c, tenants.findById(c.getTenantId()).map(Tenant::getSlug).orElse(null), blocked);
    }

    private Map<UUID, Tenant> myTenants(Jwt jwt) {
        return tenants.findAllById(tenantAgents.tenantIdsFor(jwt.getSubject())).stream()
            .collect(Collectors.toMap(Tenant::getId, Function.identity()));
    }

    private AgentRef agent(Jwt jwt) {
        return new AgentRef(jwt.getSubject(), agentTokens.displayName(jwt));
    }

    private static ApiException notFound() {
        return ApiException.notFound("CONVERSATION_NOT_FOUND", "Conversation not found");
    }
}
