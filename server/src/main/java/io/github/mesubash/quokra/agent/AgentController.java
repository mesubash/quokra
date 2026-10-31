package io.github.mesubash.quokra.agent;

import io.github.mesubash.quokra.agent.AgentViews.ConversationView;
import io.github.mesubash.quokra.agent.AgentViews.EventView;
import io.github.mesubash.quokra.agent.AgentViews.Summary;
import io.github.mesubash.quokra.attachment.Images;
import io.github.mesubash.quokra.canned.CannedReplies;
import io.github.mesubash.quokra.common.RateLimiter;
import io.github.mesubash.quokra.presence.AgentPresence;
import io.github.mesubash.quokra.conversation.Enums.SenderType;
import io.github.mesubash.quokra.realtime.TypingSignals;
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
import io.github.mesubash.quokra.security.TenantAccess;
import io.github.mesubash.quokra.tenant.Tenant;
import io.github.mesubash.quokra.tenant.TenantAgents;
import io.github.mesubash.quokra.tenant.TenantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
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
import org.springframework.web.multipart.MultipartFile;

// Agent endpoints. An agent only sees tenants they're a member of; anything else looks like it doesn't exist.
@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 200;
    static final int HISTORY_LIMIT = 100;
    static final int HISTORY_ITEMS = 20;

    // agent / admin: which parts of the console this person may use (admin: server admin, every company).
    // manages: the companies whose settings they may change as company admin.
    record Me(String sub, String name, List<String> tenants, boolean agent, boolean admin, List<String> manages) {}

    // attachments: agents can send images in this tenant's chats. primaryColor: the company's brand colour.
    record TenantItem(UUID id, String slug, String displayName, boolean attachments, String primaryColor) {}

    record Page<T>(List<T> items, String nextCursor) {}

    record Detail(ConversationView conversation, List<MessageView> messages, List<EventView> events,
                  List<NoteView> notes) {}

    record One(ConversationView conversation) {}

    // replyTo: an earlier message this one answers (optional).
    record ReplyRequest(@Size(max = 64) String clientMessageId, String body, UUID replyTo) {}

    record ReplyResponse(MessageView message) {}

    record TransferRequest(@Size(max = 255) String agentSub) {}

    record NoteRequest(String body) {}

    record NoteResponse(NoteView note) {}

    record ReadRequest(@NotNull UUID messageId) {}

    // status: ONLINE or AWAY; missing keeps the current one.
    record PresenceRequest(AgentPresence.Status status) {}

    record PresenceView(AgentPresence.Status status) {}

    // Someone a chat can be transferred to, and whether they're answering chats now.
    record MemberView(String sub, String name, boolean online) {}

    private final ConversationService service;
    private final TenantRepository tenants;
    private final TenantAgents tenantAgents;
    private final ContactRepository contacts;
    private final AgentReads reads;
    private final AgentJwtConverter agentTokens;
    private final CannedReplies replies;
    private final TypingSignals typing;
    private final RateLimiter rateLimiter;
    private final AgentPresence presence;
    private final TenantAccess access;

    public AgentController(ConversationService service, TenantRepository tenants, TenantAgents tenantAgents,
                           ContactRepository contacts, AgentReads reads, AgentJwtConverter agentTokens,
                           CannedReplies replies, TypingSignals typing, RateLimiter rateLimiter,
                           AgentPresence presence, TenantAccess access) {
        this.access = access;
        this.presence = presence;
        this.replies = replies;
        this.typing = typing;
        this.rateLimiter = rateLimiter;
        this.reads = reads;
        this.agentTokens = agentTokens;
        this.service = service;
        this.tenants = tenants;
        this.tenantAgents = tenantAgents;
        this.contacts = contacts;
    }

    @GetMapping("/me")
    public Me me(@AuthenticationPrincipal Jwt jwt, Authentication auth) {
        String name = agentTokens.displayName(jwt);
        String email = agentTokens.verifiedEmail(jwt);
        // The console calls this first, so it's where invites by email are picked up.
        if (email != null) {
            tenantAgents.acceptInvites(jwt.getSubject(), name, email);
        }
        tenantAgents.rememberProfile(jwt.getSubject(), name, email);
        return new Me(jwt.getSubject(), name, myTenants(jwt).values().stream().map(Tenant::getSlug).sorted().toList(),
            hasRole(auth, AgentJwtConverter.AGENT_ROLE), hasRole(auth, AgentJwtConverter.ADMIN_ROLE),
            List.copyOf(access.managed(auth)));
    }

    private static boolean hasRole(Authentication auth, String role) {
        return auth.getAuthorities().stream().anyMatch(a -> ("ROLE_" + role).equals(a.getAuthority()));
    }

    @GetMapping("/tenants")
    public Page<TenantItem> tenants(@AuthenticationPrincipal Jwt jwt) {
        return new Page<>(myTenants(jwt).values().stream()
            .map(t -> new TenantItem(t.getId(), t.getSlug(), t.getDisplayName(), service.imagesAllowed(t),
                t.getPrimaryColor()))
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

    // waiting: the queue; mine: my open chats; myUnread: unread customer messages in them.
    record CountsView(long waiting, long mine, long myUnread) {}

    @GetMapping("/conversations/counts")
    public CountsView counts(@AuthenticationPrincipal Jwt jwt) {
        var tenantIds = myTenants(jwt).keySet();
        var counts = service.counts(tenantIds, jwt.getSubject());
        return new CountsView(counts.waiting(), counts.mine(), reads.unreadInMyChats(jwt.getSubject(), tenantIds));
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
            service.addAgentMessage(id, agent(jwt), request.clientMessageId(), request.body(), request.replyTo())));
    }

    // An image (multipart field "file") from the agent who has the chat.
    @PostMapping(path = "/conversations/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ReplyResponse sendImage(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                   @RequestParam MultipartFile file,
                                   @RequestParam(required = false) @Size(max = 64) String clientMessageId,
                                   @RequestParam(required = false) UUID replyTo)
            throws IOException {
        visible(jwt, id);
        rateLimiter.check("uploads:agent:" + jwt.getSubject(), RateLimiter.UPLOADS_PER_MINUTE);
        return new ReplyResponse(MessageView.of(service.addAgentImage(id, agent(jwt), clientMessageId,
            file.getOriginalFilename(), file.getBytes(), replyTo)));
    }

    @GetMapping("/conversations/{id}/messages/{messageId}/attachment")
    public ResponseEntity<byte[]> image(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                        @PathVariable UUID messageId) {
        visible(jwt, id);
        var image = service.image(id, messageId);
        return Images.response(image.bytes(), image.contentType(), image.name());
    }

    // The agent is typing a reply; the customer sees "<name> is typing…". Nothing is stored.
    @PostMapping("/conversations/{id}/typing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void typing(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        visible(jwt, id);
        rateLimiter.check("typing:" + jwt.getSubject(), RateLimiter.TYPING_PER_MINUTE);
        typing.typing(id, SenderType.AGENT, agentTokens.displayName(jwt));
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

    // The same customer's other conversations, for the details panel.
    @GetMapping("/conversations/{id}/history")
    public Page<Summary> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Conversation c = visible(jwt, id);
        String slug = tenants.findById(c.getTenantId()).map(Tenant::getSlug).orElse(null);
        // ponytail: one query per row for the preview, like the lists; at most 20 rows.
        List<Summary> items = service.history(id, HISTORY_ITEMS).stream()
            .map(h -> Summary.of(h, slug, service.lastMessage(h.getId()).orElse(null), 0))
            .toList();
        return new Page<>(items, null);
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
    public Page<MemberView> agents(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        List<TenantAgents.Member> members = tenantAgents.members(visible(jwt, id).getTenantId());
        var online = presence.online(members.stream().map(TenantAgents.Member::sub).toList());
        return new Page<>(members.stream().map(m -> new MemberView(m.sub(), m.name(), online.contains(m.sub())))
            .toList(), null);
    }

    // The open console checks in every minute; the agent's status is ONLINE or AWAY (see AgentPresence).
    @PostMapping("/presence")
    public PresenceView presence(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) PresenceRequest request) {
        return new PresenceView(presence.checkIn(jwt.getSubject(), request == null ? null : request.status()));
    }

    // Saved replies of the conversation's tenant.
    @GetMapping("/conversations/{id}/replies")
    public Page<CannedReplies.Reply> replies(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return new Page<>(replies.list(visible(jwt, id).getTenantId()), null);
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
