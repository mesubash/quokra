package io.github.mesubash.quokra.realtime;

import io.github.mesubash.quokra.conversation.ConversationUpdated;
import io.github.mesubash.quokra.conversation.MessageCreated;
import io.github.mesubash.quokra.conversation.Status;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// Sends conversation changes to subscribers, only after the change is committed.
@Component
class RealtimePublisher {

    record Event(String type, UUID conversationId, Instant at, Object data) {}

    private final SimpMessagingTemplate messaging;
    private final Clock clock;

    RealtimePublisher(SimpMessagingTemplate messaging, Clock clock) {
        this.messaging = messaging;
        this.clock = clock;
    }

    @TransactionalEventListener
    void on(MessageCreated event) {
        send("message.created", event.conversationId(), event.message());
    }

    @TransactionalEventListener
    void on(ConversationUpdated event) {
        send("conversation.updated", event.conversationId(), event.conversation());
        String queueType = event.snapshot().status() == Status.WAITING ? "conversation.waiting" : "conversation.updated";
        messaging.convertAndSend(StompAuthInterceptor.queueTopic(event.tenantId()),
            new Event(queueType, event.conversationId(), clock.instant(), event.snapshot()));
    }

    private void send(String type, UUID conversationId, Object data) {
        messaging.convertAndSend(StompAuthInterceptor.CONVERSATION_TOPIC + conversationId,
            new Event(type, conversationId, clock.instant(), data));
    }
}
