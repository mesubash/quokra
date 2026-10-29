package io.github.mesubash.quokra.conversation;

import io.github.mesubash.quokra.bot.BotConfig;
import io.github.mesubash.quokra.bot.BotConfigRegistry;
import io.github.mesubash.quokra.bot.BotEngine;
import io.github.mesubash.quokra.common.ApiException;
import io.github.mesubash.quokra.conversation.Enums.EventType;
import io.github.mesubash.quokra.conversation.Enums.HandoffReason;
import io.github.mesubash.quokra.conversation.Enums.SenderType;
import io.github.mesubash.quokra.tenant.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

    public record Started(Conversation conversation, boolean created) {}

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ConversationEventRepository events;
    private final TenantRepository tenants;
    private final BotConfigRegistry bots;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public ConversationService(ConversationRepository conversations, MessageRepository messages,
                               ConversationEventRepository events, TenantRepository tenants,
                               BotConfigRegistry bots, ApplicationEventPublisher publisher, Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.events = events;
        this.tenants = tenants;
        this.bots = bots;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Transactional
    public Started startOrResume(UUID tenantId, UUID visitorId) {
        var latest = conversations.findFirstByTenantIdAndVisitorIdOrderByCreatedAtDesc(tenantId, visitorId);
        if (latest.isPresent()) {
            return new Started(latest.get(), false);
        }
        Instant now = clock.instant();
        Conversation conversation = conversations.save(Conversation.start(tenantId, visitorId, now));
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

        String text = optionId != null ? optionTitle(conversation.getTenantId(), optionId) : checkBody(body);

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

        if (conversation.getStatus() == Status.BOT) {
            botReply(conversation, text, optionId, now);
        }
        return message;
    }

    @Transactional
    public Conversation handoff(UUID conversationId, HandoffReason reason, SenderType actor) {
        Conversation conversation = get(conversationId);
        String text = botFor(conversation.getTenantId()).map(BotConfig::handoffMessage).orElse(HANDOFF_MESSAGE);
        handOff(conversation, reason, actor, text, clock.instant());
        return conversation;
    }

    // The visitor's latest conversation, open or resolved.
    @Transactional(readOnly = true)
    public Optional<Conversation> current(UUID tenantId, UUID visitorId) {
        return conversations.findFirstByTenantIdAndVisitorIdOrderByCreatedAtDesc(tenantId, visitorId);
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
        conversation.moveTo(StateMachine.next(conversation.getStatus(), Transition.HANDOFF), now);
        conversation.handOff(reason, now);
        conversation.touch(now);
        events.save(ConversationEvent.of(conversation.getId(), EventType.HANDOFF, actor, null, reason.name(), now));
        save(Message.create(conversation.getId(), SenderType.SYSTEM, null, null, text, null, null, now));
        publishUpdate(conversation);
    }

    private Message save(Message message) {
        Message saved = messages.save(message);
        publisher.publishEvent(new MessageCreated(saved.getConversationId(), MessageView.of(saved)));
        return saved;
    }

    private void publishUpdate(Conversation conversation) {
        publisher.publishEvent(new ConversationUpdated(conversation.getId(), CustomerConversationView.of(conversation)));
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
