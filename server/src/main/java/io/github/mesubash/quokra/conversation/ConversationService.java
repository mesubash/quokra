package io.github.mesubash.quokra.conversation;

import io.github.mesubash.quokra.common.ApiException;
import io.github.mesubash.quokra.conversation.Enums.EventType;
import io.github.mesubash.quokra.conversation.Enums.HandoffReason;
import io.github.mesubash.quokra.conversation.Enums.SenderType;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// All writes to conversations and messages go through here.
@Service
public class ConversationService {

    // Replaced by the tenant's bot config in step 4.
    static final String GREETING = "Hi! How can we help?";
    static final String HANDOFF_MESSAGE = "Connecting you with our team. Someone will reply shortly.";

    public record Started(Conversation conversation, boolean created) {}

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ConversationEventRepository events;
    private final Clock clock;

    public ConversationService(ConversationRepository conversations, MessageRepository messages,
                               ConversationEventRepository events, Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.events = events;
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
        messages.save(Message.create(conversation.getId(), SenderType.BOT, null, null, GREETING, null, now));
        return new Started(conversation, true);
    }

    @Transactional
    public Message addCustomerMessage(UUID conversationId, String clientMessageId, String body) {
        checkBody(body);
        Conversation conversation = get(conversationId);

        if (clientMessageId != null) {
            var existing = messages.findByConversationIdAndClientMessageId(conversationId, clientMessageId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        Instant now = clock.instant();
        if (conversation.getStatus() == Status.RESOLVED) {
            conversation.moveTo(StateMachine.next(conversation.getStatus(), Transition.REOPEN), now);
            conversation.reopen(now);
            events.save(ConversationEvent.of(conversationId, EventType.REOPENED, SenderType.CUSTOMER, null, null, now));
        }
        conversation.touch(now);
        return messages.save(Message.create(conversationId, SenderType.CUSTOMER, null, null, body, clientMessageId, now));
    }

    @Transactional
    public Conversation handoff(UUID conversationId, HandoffReason reason, SenderType actor) {
        Conversation conversation = get(conversationId);
        Instant now = clock.instant();
        conversation.moveTo(StateMachine.next(conversation.getStatus(), Transition.HANDOFF), now);
        conversation.handOff(reason, now);
        conversation.touch(now);
        events.save(ConversationEvent.of(conversationId, EventType.HANDOFF, actor, null, reason.name(), now));
        messages.save(Message.create(conversationId, SenderType.SYSTEM, null, null, HANDOFF_MESSAGE, null, now));
        return conversation;
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

    private static void checkBody(String body) {
        if (body == null || body.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Message can't be empty");
        }
        if (body.length() > Message.MAX_BODY_LENGTH) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "MESSAGE_TOO_LONG",
                "Message is longer than " + Message.MAX_BODY_LENGTH + " characters");
        }
    }
}
