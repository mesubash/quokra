package io.github.mesubash.quokra.realtime;

import io.github.mesubash.quokra.conversation.ConversationTransferred;
import io.github.mesubash.quokra.conversation.ConversationUpdated;
import io.github.mesubash.quokra.conversation.Enums.SenderType;
import io.github.mesubash.quokra.conversation.MessageCreated;
import io.github.mesubash.quokra.conversation.MessageView;
import io.github.mesubash.quokra.conversation.NoteCreated;
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

    // On the queue topic, so the console can update a row's preview and unread count without reloading.
    record Activity(UUID id, String lastMessagePreview, Instant lastMessageAt, SenderType senderType,
                    String assigneeSub, String customerName) {}

    // A private note was added; tells the assigned agent without sending them the note topic.
    record NoteAdded(UUID id, String authorSub, String authorName, String assigneeSub, String customerName,
                     String preview) {}

    record Transferred(UUID id, String toSub, String toName, String byName, String customerName) {}

    static final int PREVIEW_LENGTH = 120;

    private final SimpMessagingTemplate messaging;
    private final Clock clock;

    RealtimePublisher(SimpMessagingTemplate messaging, Clock clock) {
        this.messaging = messaging;
        this.clock = clock;
    }

    @TransactionalEventListener
    void on(MessageCreated event) {
        send("message.created", event.conversationId(), event.message());
        MessageView m = event.message();
        messaging.convertAndSend(StompAuthInterceptor.queueTopic(event.tenantId()),
            new Event("conversation.activity", event.conversationId(), clock.instant(),
                new Activity(event.conversationId(), preview(m.body()), m.createdAt(), m.senderType(), event.assigneeSub(),
                    event.customerName())));
    }

    @TransactionalEventListener
    void on(NoteCreated event) {
        messaging.convertAndSend(StompAuthInterceptor.CONVERSATION_TOPIC + event.conversationId() + StompAuthInterceptor.NOTES_SUFFIX,
            new Event("note.created", event.conversationId(), clock.instant(), event.note()));
        var note = event.note();
        messaging.convertAndSend(StompAuthInterceptor.queueTopic(event.tenantId()),
            new Event("note.added", event.conversationId(), clock.instant(),
                new NoteAdded(event.conversationId(), note.author().sub(), note.author().name(), event.assigneeSub(),
                    event.customerName(), preview(note.body()))));
    }

    @TransactionalEventListener
    void on(ConversationTransferred event) {
        messaging.convertAndSend(StompAuthInterceptor.queueTopic(event.tenantId()),
            new Event("conversation.transferred", event.conversationId(), clock.instant(),
                new Transferred(event.conversationId(), event.to().sub(), event.to().name(), event.byName(),
                    event.customerName())));
    }

    private static String preview(String body) {
        return body.length() > PREVIEW_LENGTH ? body.substring(0, PREVIEW_LENGTH) : body;
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
