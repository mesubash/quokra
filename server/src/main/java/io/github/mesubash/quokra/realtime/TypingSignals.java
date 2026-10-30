package io.github.mesubash.quokra.realtime;

import io.github.mesubash.quokra.conversation.Enums.SenderType;
import java.time.Clock;
import java.util.UUID;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

// "Someone is typing", for the other side of a chat. Sent straight away and never stored; clients show it
// for a few seconds and send it again while the person keeps typing.
@Component
public class TypingSignals {

    // name: the agent's name; null for the customer.
    public record Typing(SenderType senderType, String name) {}

    private final SimpMessagingTemplate messaging;
    private final Clock clock;

    TypingSignals(SimpMessagingTemplate messaging, Clock clock) {
        this.messaging = messaging;
        this.clock = clock;
    }

    public void typing(UUID conversationId, SenderType senderType, String name) {
        messaging.convertAndSend(StompAuthInterceptor.CONVERSATION_TOPIC + conversationId,
            new RealtimePublisher.Event("typing", conversationId, clock.instant(), new Typing(senderType, name)));
    }
}
