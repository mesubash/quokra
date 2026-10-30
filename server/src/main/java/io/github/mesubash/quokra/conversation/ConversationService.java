package io.github.mesubash.quokra.conversation;

import io.github.mesubash.quokra.bot.BotConfig;
import io.github.mesubash.quokra.bot.BotConfigRegistry;
import io.github.mesubash.quokra.bot.BotEngine;
import io.github.mesubash.quokra.common.ApiException;
import io.github.mesubash.quokra.contact.Contact;
import io.github.mesubash.quokra.contact.ContactRepository;
import io.github.mesubash.quokra.conversation.Enums.EventType;
import io.github.mesubash.quokra.conversation.Enums.HandoffReason;
import io.github.mesubash.quokra.conversation.Enums.SenderType;
import io.github.mesubash.quokra.tenant.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// All writes to conversations and messages go through here.
@Slf4j
@Service
public class ConversationService {

    // Used when a tenant's bot config can't be found.
    static final String GREETING = "Hi! How can we help?";
    static final String HANDOFF_MESSAGE = "Connecting you with our team. Someone will reply shortly.";
    static final String RESOLVED_MESSAGE = "This conversation is closed. Write again any time.";
    static final String BACK_TO_BOT_MESSAGE = "Anything else I can help with?";
    // Before a guest reaches the team, the bot asks for their email, then their name (which they can skip).
    // Tenants can change these wording in their bot file (texts); these are the defaults.
    static final String ASK_EMAIL = BotConfig.Texts.DEFAULT.askEmail();
    static final String BAD_EMAIL = BotConfig.Texts.DEFAULT.badEmail();
    static final String ASK_NAME = BotConfig.Texts.DEFAULT.askName();
    static final String SKIP_OPTION = "skip";
    static final int MAX_NAME_LENGTH = 100;
    static final int MAX_EMAIL_LENGTH = 254;
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");

    public record Started(Conversation conversation, boolean created) {}

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ConversationEventRepository events;
    private final TenantRepository tenants;
    private final ContactRepository contacts;
    private final NoteRepository notes;
    private final BotConfigRegistry bots;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public ConversationService(ConversationRepository conversations, MessageRepository messages,
                               ConversationEventRepository events, TenantRepository tenants,
                               ContactRepository contacts, NoteRepository notes, BotConfigRegistry bots,
                               ApplicationEventPublisher publisher, Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.events = events;
        this.tenants = tenants;
        this.contacts = contacts;
        this.notes = notes;
        this.bots = bots;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Transactional
    public Started startOrResume(UUID tenantId, UUID visitorId) {
        return startOrResume(tenantId, visitorId, null);
    }

    // A logged-in customer gets their latest conversation from any device; a guest, this visitor's.
    @Transactional
    public Started startOrResume(UUID tenantId, UUID visitorId, CustomerIdentity customer) {
        var latest = current(tenantId, visitorId, customer == null ? null : customer.sub());
        if (latest.isPresent()) {
            return new Started(latest.get(), false);
        }
        Instant now = clock.instant();
        Conversation started = Conversation.start(tenantId, visitorId, now);
        if (customer != null) {
            started.identify(customer, now);
        }
        Conversation conversation = conversations.save(started);
        events.save(ConversationEvent.of(conversation.getId(), EventType.CREATED, SenderType.CUSTOMER, null, null, now));

        Optional<BotConfig> bot = botFor(tenantId);
        save(Message.create(conversation.getId(), SenderType.BOT, null, null,
            bot.map(BotConfig::greeting).orElse(GREETING), null, bot.map(BotConfig::options).orElse(null), now));
        return new Started(conversation, true);
    }

    // Stores the message, then lets the bot reply while the conversation is with the bot.
    // Send either body (typed text) or optionId (a menu button).
    @Transactional
    public Message addCustomerMessage(UUID conversationId, String clientMessageId, String body, String optionId) {
        Conversation conversation = get(conversationId);

        if (clientMessageId != null) {
            var existing = messages.findByConversationIdAndClientMessageId(conversationId, clientMessageId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        requireNotBlocked(conversation);
        boolean collecting = conversation.getPendingHandoff() != null;
        String text = collecting && SKIP_OPTION.equals(optionId) ? textsFor(conversation).skip()
            : optionId != null ? optionTitle(conversation.getTenantId(), optionId) : checkBody(body);

        Instant now = clock.instant();
        if (conversation.getStatus() == Status.RESOLVED) {
            conversation.moveTo(StateMachine.next(conversation.getStatus(), Transition.REOPEN), now);
            conversation.reopen(now);
            events.save(ConversationEvent.of(conversationId, EventType.REOPENED, SenderType.CUSTOMER, null, null, now));
            publishUpdate(conversation);
        }
        conversation.touch(now);
        Message message = save(
            Message.create(conversationId, SenderType.CUSTOMER, null, null, text, clientMessageId, null, now));

        if (collecting) {
            collectContact(conversation, text, optionId, now);
        } else if (conversation.getStatus() == Status.BOT) {
            botReply(conversation, text, optionId, now);
        }
        return message;
    }

    @Transactional
    public Conversation handoff(UUID conversationId, HandoffReason reason, SenderType actor) {
        Conversation conversation = get(conversationId);
        requireNotBlocked(conversation);
        handOff(conversation, reason, actor, handoffText(conversation), clock.instant());
        return conversation;
    }

    // The customer's name and email. Links the conversation to the contact with that email (creating it
    // the first time), then carries on with a handoff that was waiting for these details.
    // ponytail: the email isn't verified, so it only links conversations for agents; customers never see
    // another conversation through it.
    @Transactional
    public Conversation addContact(UUID conversationId, String name, String email) {
        Conversation conversation = get(conversationId);
        requireNotBlocked(conversation);
        linkContact(conversation, name, email, clock.instant());
        return conversation;
    }

    private void linkContact(Conversation conversation, String name, String email, Instant now) {
        String cleanName = name == null || name.isBlank() ? null : name.strip();
        String cleanEmail = email.strip();
        Contact contact = contacts.findByEmail(conversation.getTenantId(), cleanEmail)
            .orElseGet(() -> contacts.save(Contact.create(conversation.getTenantId(), cleanName, cleanEmail, now)));
        if (contact.isBlocked()) {
            throw blocked();
        }
        contact.rename(cleanName, now);
        conversation.linkContact(contact.getId(), contact.getName(), contact.getEmail(), now);
        events.save(ConversationEvent.of(conversation.getId(), EventType.CONTACT_ADDED, SenderType.CUSTOMER, null, null,
            now));

        HandoffReason pending = conversation.getPendingHandoff();
        if (pending != null) {
            handOff(conversation, pending, SenderType.CUSTOMER, handoffText(conversation), now);
        } else {
            publishUpdate(conversation);
        }
    }

    // The customer's reply while the bot is asking for their details: first the email, then the name.
    private void collectContact(Conversation conversation, String text, String optionId, Instant now) {
        if (conversation.getCustomerEmail() == null) {
            String email = text.strip();
            if (email.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(email).matches()) {
                botMessage(conversation, textsFor(conversation).badEmail(), null, now);
                return;
            }
            if (contacts.findByEmail(conversation.getTenantId(), email).map(Contact::isBlocked).orElse(false)) {
                throw blocked();
            }
            conversation.rememberEmail(email, now);
            BotConfig.Texts texts = textsFor(conversation);
            botMessage(conversation, texts.askName(), List.of(new BotConfig.Option(SKIP_OPTION, texts.skip())), now);
            publishUpdate(conversation);
            return;
        }
        String name = SKIP_OPTION.equals(optionId) ? null : text.strip();
        if (name != null && name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH);
        }
        linkContact(conversation, name, conversation.getCustomerEmail(), now);
    }

    // Takes a waiting conversation. If two agents try at once, the second gets a conflict when committing.
    @Transactional
    public Conversation accept(UUID conversationId, AgentRef agent) {
        Conversation conversation = get(conversationId);
        if (conversation.getStatus() == Status.HUMAN) {
            throw alreadyAccepted();
        }
        Instant now = clock.instant();
        conversation.moveTo(StateMachine.next(conversation.getStatus(), Transition.ACCEPT), now);
        conversation.assign(agent.sub(), agent.name());
        conversation.touch(now);
        events.save(ConversationEvent.byAgent(conversationId, EventType.ACCEPTED, agent, now));
        save(Message.create(conversationId, SenderType.SYSTEM, null, null, agent.name() + " joined the chat", null, null, now));
        publishUpdate(conversation);
        return conversation;
    }

    @Transactional
    public Message addAgentMessage(UUID conversationId, AgentRef agent, String clientMessageId, String body) {
        Conversation conversation = get(conversationId);
        if (clientMessageId != null) {
            var existing = messages.findByConversationIdAndClientMessageId(conversationId, clientMessageId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        String text = checkBody(body);
        if (conversation.getStatus() != Status.HUMAN) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "Accept the conversation before replying");
        }
        requireAssignee(conversation, agent);
        Instant now = clock.instant();
        conversation.touch(now);
        return save(Message.create(conversationId, SenderType.AGENT, agent.sub(), agent.name(), text, clientMessageId,
            null, now));
    }

    // Closes a conversation. Waiting ones can be closed by any agent (e.g. spam); taken ones only by the assignee.
    @Transactional
    public Conversation resolve(UUID conversationId, AgentRef agent) {
        Conversation conversation = get(conversationId);
        if (conversation.getStatus() == Status.HUMAN) {
            requireAssignee(conversation, agent);
        }
        Instant now = clock.instant();
        conversation.moveTo(StateMachine.next(conversation.getStatus(), Transition.RESOLVE), now);
        conversation.touch(now);
        events.save(ConversationEvent.byAgent(conversationId, EventType.RESOLVED, agent, now));
        save(Message.create(conversationId, SenderType.SYSTEM, null, null, RESOLVED_MESSAGE, null, null, now));
        publishUpdate(conversation);
        return conversation;
    }

    // Blocks the customer's contact and closes the conversation. They can't chat again until unblocked.
    @Transactional
    public Conversation block(UUID conversationId, AgentRef agent) {
        Conversation conversation = get(conversationId);
        if (conversation.getContactId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_CONTACT", "This conversation has no contact to block");
        }
        if (conversation.getStatus() == Status.HUMAN) {
            requireAssignee(conversation, agent);
        }
        Instant now = clock.instant();
        contacts.findById(conversation.getContactId()).ifPresent(c -> c.block(agent.sub(), now));
        events.save(ConversationEvent.byAgent(conversationId, EventType.BLOCKED, agent, now));
        if (conversation.isOpen()) {
            conversation.moveTo(StateMachine.next(conversation.getStatus(), Transition.RESOLVE), now);
            conversation.touch(now);
            save(Message.create(conversationId, SenderType.SYSTEM, null, null, RESOLVED_MESSAGE, null, null, now));
        }
        publishUpdate(conversation);
        return conversation;
    }

    @Transactional
    public void unblock(UUID contactId) {
        contacts.findById(contactId).ifPresent(c -> c.unblock(clock.instant()));
    }

    // An agent picks a closed conversation up again, e.g. to follow up.
    @Transactional
    public Conversation reopen(UUID conversationId, AgentRef agent) {
        Conversation conversation = get(conversationId);
        requireNotBlocked(conversation);
        Instant now = clock.instant();
        conversation.moveTo(StateMachine.next(conversation.getStatus(), Transition.AGENT_REOPEN), now);
        conversation.assign(agent.sub(), agent.name());
        conversation.touch(now);
        events.save(ConversationEvent.byAgent(conversationId, EventType.REOPENED, agent, now));
        save(Message.create(conversationId, SenderType.SYSTEM, null, null, agent.name() + " reopened the chat", null,
            null, now));
        publishUpdate(conversation);
        return conversation;
    }

    // Hands the conversation to another agent, or back to the queue when `to` is null.
    @Transactional
    public Conversation transfer(UUID conversationId, AgentRef agent, AgentRef to) {
        Conversation conversation = get(conversationId);
        if (conversation.getStatus() != Status.HUMAN) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "Only an accepted conversation can be transferred");
        }
        requireAssignee(conversation, agent);
        Instant now = clock.instant();
        ConversationEvent event = ConversationEvent.byAgent(conversationId, EventType.TRANSFERRED, agent, now);
        if (to == null) {
            conversation.moveTo(StateMachine.next(conversation.getStatus(), Transition.TRANSFER_TO_QUEUE), now);
            conversation.release();
            save(Message.create(conversationId, SenderType.SYSTEM, null, null, HANDOFF_MESSAGE, null, null, now));
        } else {
            conversation.assign(to.sub(), to.name());
            event.setReason(to.name());
            save(Message.create(conversationId, SenderType.SYSTEM, null, null, to.name() + " joined the chat", null,
                null, now));
        }
        conversation.touch(now);
        events.save(event);
        publishUpdate(conversation);
        return conversation;
    }

    // Any agent of the tenant can leave a note; customers never see them.
    @Transactional
    public ConversationNote addNote(UUID conversationId, AgentRef agent, String body) {
        get(conversationId);
        ConversationNote note = notes.save(ConversationNote.create(conversationId, agent, checkBody(body), clock.instant()));
        publisher.publishEvent(new NoteCreated(conversationId, NoteView.of(note)));
        return note;
    }

    @Transactional(readOnly = true)
    public List<ConversationNote> notes(UUID conversationId) {
        return notes.findByConversationIdOrderByIdAsc(conversationId);
    }

    @Transactional
    public Conversation returnToBot(UUID conversationId, AgentRef agent) {
        Conversation conversation = get(conversationId);
        Status next = StateMachine.next(conversation.getStatus(), Transition.RETURN_TO_BOT);
        requireAssignee(conversation, agent);
        Instant now = clock.instant();
        conversation.moveTo(next, now);
        conversation.unassign();
        conversation.touch(now);
        events.save(ConversationEvent.byAgent(conversationId, EventType.RETURNED_TO_BOT, agent, now));
        save(Message.create(conversationId, SenderType.BOT, null, null, BACK_TO_BOT_MESSAGE, null,
            botFor(conversation.getTenantId()).map(BotConfig::options).orElse(null), now));
        publishUpdate(conversation);
        return conversation;
    }

    // WAITING: the queue (urgent first, longest wait first). Others: most recent first.
    // `after` continues from the last row of the previous page.
    @Transactional(readOnly = true)
    // search: matches the customer's name or email, or any message, ignoring case. Null or blank: everything.
    public List<Conversation> list(Collection<UUID> tenantIds, Status status, String assigneeSub, String search,
                                   PageCursor after, int limit) {
        if (tenantIds.isEmpty()) {
            return List.of();
        }
        Limit size = Limit.of(limit);
        String q = likePattern(search);
        if (status == Status.WAITING) {
            return after == null ? conversations.findQueue(tenantIds, q, size)
                : conversations.findQueueAfter(tenantIds, after.rank(), after.at(), after.id(), q, size);
        }
        return after == null ? conversations.findRecent(tenantIds, status, assigneeSub, q, size)
            : conversations.findRecentAfter(tenantIds, status, assigneeSub, after.at(), after.id(), q, size);
    }

    // "%text%" for LIKE, with the text's own % _ and \ taken literally.
    static String likePattern(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String escaped = search.strip().toLowerCase(java.util.Locale.ROOT)
            .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    public record Counts(long waiting, long mine) {}

    // For badges: how many are waiting, and how many open chats the agent has.
    @Transactional(readOnly = true)
    public Counts counts(Collection<UUID> tenantIds, String agentSub) {
        if (tenantIds.isEmpty()) {
            return new Counts(0, 0);
        }
        return new Counts(conversations.countByTenantIdInAndStatus(tenantIds, Status.WAITING),
            conversations.countByTenantIdInAndStatusAndAssigneeSub(tenantIds, Status.HUMAN, agentSub));
    }

    // The customer's other conversations with this tenant, newest first. Empty for an anonymous guest.
    @Transactional(readOnly = true)
    public List<Conversation> history(UUID conversationId, int limit) {
        Conversation c = get(conversationId);
        if (c.getContactId() == null && c.getCustomerSub() == null) {
            return List.of();
        }
        return conversations.findHistory(c.getTenantId(), c.getId(), c.getContactId(), c.getCustomerSub(), Limit.of(limit));
    }

    @Transactional(readOnly = true)
    public Optional<Message> lastMessage(UUID conversationId) {
        return messages.findLatest(conversationId, Limit.of(1)).stream().findFirst();
    }

    public static ApiException alreadyAccepted() {
        return new ApiException(HttpStatus.CONFLICT, "ALREADY_ACCEPTED", "Another agent already took this conversation");
    }

    // The visitor's latest conversation, open or resolved.
    @Transactional(readOnly = true)
    public Optional<Conversation> current(UUID tenantId, UUID visitorId) {
        return current(tenantId, visitorId, null);
    }

    // For a logged-in customer, their latest conversation on any device.
    @Transactional(readOnly = true)
    public Optional<Conversation> current(UUID tenantId, UUID visitorId, String customerSub) {
        return customerSub != null
            ? conversations.findFirstByTenantIdAndCustomerSubOrderByCreatedAtDesc(tenantId, customerSub)
            : conversations.findFirstByTenantIdAndVisitorIdOrderByCreatedAtDesc(tenantId, visitorId);
    }

    // The visitor logged in on the host site. Their open guest conversation becomes the customer's, or, if the
    // customer already has an open one (e.g. on another device), its messages move there.
    // Returns the visitor id the new session should use.
    @Transactional
    public UUID identify(UUID tenantId, UUID visitorId, CustomerIdentity customer) {
        Instant now = clock.instant();
        Optional<Conversation> visitors = conversations.findFirstByTenantIdAndVisitorIdOrderByCreatedAtDesc(tenantId, visitorId)
            .filter(Conversation::isOpen);
        Optional<Conversation> customers = conversations.findFirstByTenantIdAndCustomerSubOrderByCreatedAtDesc(tenantId, customer.sub())
            .filter(Conversation::isOpen);

        // This browser's open chat belongs to someone else who logged in here before: start fresh.
        if (visitors.isPresent() && visitors.get().getCustomerSub() != null
                && !visitors.get().getCustomerSub().equals(customer.sub())) {
            customers.ifPresent(c -> refreshIdentity(c, customer, now));
            return UUID.randomUUID();
        }
        Optional<Conversation> guest = visitors.filter(c -> c.getCustomerSub() == null);
        if (guest.isEmpty()) {
            customers.ifPresent(c -> refreshIdentity(c, customer, now));
            return visitorId;
        }

        Conversation g = guest.get();
        if (customers.isEmpty()) {
            g.identify(customer, now);
            events.save(ConversationEvent.of(g.getId(), EventType.IDENTIFIED, SenderType.CUSTOMER, null, null, now));
            if (g.getPendingHandoff() != null) {
                // They were being asked for their email; now we know who they are.
                handOff(g, g.getPendingHandoff(), SenderType.CUSTOMER, handoffText(g), now);
            } else {
                publishUpdate(g);
            }
            return visitorId;
        }

        Conversation target = customers.get();
        messages.flush();
        messages.moveAll(g.getId(), target.getId());
        g.closeAsMerged(now);
        events.save(ConversationEvent.of(g.getId(), EventType.MERGED, SenderType.CUSTOMER, null, target.getId().toString(), now));
        events.save(ConversationEvent.of(target.getId(), EventType.IDENTIFIED, SenderType.CUSTOMER, null, null, now));
        target.identify(customer, now);
        target.touch(now);
        save(Message.create(target.getId(), SenderType.SYSTEM, null, null,
            "Messages from another device were added to this chat.", null, null, now));
        publishUpdate(g);
        publishUpdate(target);
        return visitorId;
    }

    private void refreshIdentity(Conversation conversation, CustomerIdentity customer, Instant now) {
        conversation.identify(customer, now);
        publishUpdate(conversation);
    }

    @Transactional(readOnly = true)
    public Conversation get(UUID conversationId) {
        return conversations.findById(conversationId)
            .orElseThrow(() -> ApiException.notFound("CONVERSATION_NOT_FOUND", "Conversation not found"));
    }

    // Oldest first. Without a cursor, returns the latest page.
    @Transactional(readOnly = true)
    public List<Message> messages(UUID conversationId, UUID after, int limit) {
        if (after != null) {
            return messages.findByConversationIdAndIdGreaterThanOrderByIdAsc(conversationId, after, Limit.of(limit));
        }
        List<Message> latest = new ArrayList<>(messages.findLatest(conversationId, Limit.of(limit)));
        Collections.reverse(latest);
        return latest;
    }

    @Transactional(readOnly = true)
    public List<ConversationEvent> events(UUID conversationId) {
        return events.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    private void botReply(Conversation conversation, String text, String optionId, Instant now) {
        BotConfig bot;
        BotEngine.Decision decision;
        try {
            bot = botFor(conversation.getTenantId())
                .orElseThrow(() -> new IllegalStateException("No bot config for tenant " + conversation.getTenantId()));
            decision = BotEngine.decide(bot, text, optionId, conversation.getFallbackCount());
        } catch (RuntimeException e) {
            // A broken bot must never leave the customer stuck: hand over to the team.
            log.warn("Bot failed for conversation {}, handing off", conversation.getId(), e);
            handOff(conversation, HandoffReason.BOT_ERROR, SenderType.BOT, HANDOFF_MESSAGE, now);
            return;
        }

        switch (decision) {
            case BotEngine.Answer answer -> {
                conversation.resetFallbacks();
                botMessage(conversation, answer.text(), bot.options(), now);
            }
            case BotEngine.Fallback fallback -> {
                conversation.countFallback();
                botMessage(conversation, fallback.text(), bot.options(), now);
            }
            case BotEngine.Handoff handoff ->
                handOff(conversation, HandoffReason.valueOf(handoff.reason().name()), SenderType.BOT, handoff.text(), now);
        }
    }

    private void botMessage(Conversation conversation, String text, List<BotConfig.Option> options, Instant now) {
        save(Message.create(conversation.getId(), SenderType.BOT, null, null, text, null, options, now));
    }

    private void handOff(Conversation conversation, HandoffReason reason, SenderType actor, String text, Instant now) {
        if (conversation.needsContact()) {
            if (conversation.getPendingHandoff() == null) {
                conversation.awaitContact(reason, now);
                conversation.touch(now);
                botMessage(conversation, textsFor(conversation).askEmail(), null, now);
                publishUpdate(conversation);
            }
            return;
        }
        conversation.moveTo(StateMachine.next(conversation.getStatus(), Transition.HANDOFF), now);
        conversation.handOff(reason, now);
        conversation.touch(now);
        events.save(ConversationEvent.of(conversation.getId(), EventType.HANDOFF, actor, null, reason.name(), now));
        save(Message.create(conversation.getId(), SenderType.SYSTEM, null, null, text, null, null, now));
        publishUpdate(conversation);
    }

    private Message save(Message message) {
        Message saved = messages.save(message);
        // Already loaded in this transaction, so this doesn't query again.
        UUID tenantId = get(saved.getConversationId()).getTenantId();
        publisher.publishEvent(new MessageCreated(saved.getConversationId(), tenantId, MessageView.of(saved)));
        return saved;
    }

    private void publishUpdate(Conversation conversation) {
        publisher.publishEvent(new ConversationUpdated(conversation.getId(), conversation.getTenantId(),
            CustomerConversationView.of(conversation), ConversationSnapshot.of(conversation)));
    }

    private BotConfig.Texts textsFor(Conversation conversation) {
        return botFor(conversation.getTenantId()).map(BotConfig::texts).orElse(BotConfig.Texts.DEFAULT);
    }

    private String handoffText(Conversation conversation) {
        return botFor(conversation.getTenantId()).map(BotConfig::handoffMessage).orElse(HANDOFF_MESSAGE);
    }

    private void requireNotBlocked(Conversation conversation) {
        if (conversation.getContactId() != null
                && contacts.findById(conversation.getContactId()).map(Contact::isBlocked).orElse(false)) {
            throw blocked();
        }
    }

    private static ApiException blocked() {
        return new ApiException(HttpStatus.FORBIDDEN, "CONTACT_BLOCKED", "This chat isn't available");
    }

    private static void requireAssignee(Conversation conversation, AgentRef agent) {
        if (!conversation.isAssignedTo(agent.sub())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_ASSIGNEE", "This conversation is assigned to someone else");
        }
    }

    private Optional<BotConfig> botFor(UUID tenantId) {
        return tenants.findById(tenantId).flatMap(tenant -> bots.find(tenant.getBotConfig()));
    }

    private String optionTitle(UUID tenantId, String optionId) {
        return botFor(tenantId).flatMap(bot -> bot.optionTitle(optionId))
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Unknown option"));
    }

    private static String checkBody(String body) {
        if (body == null || body.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Message can't be empty");
        }
        if (body.length() > Message.MAX_BODY_LENGTH) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "MESSAGE_TOO_LONG",
                "Message is longer than " + Message.MAX_BODY_LENGTH + " characters");
        }
        return body;
    }
}
