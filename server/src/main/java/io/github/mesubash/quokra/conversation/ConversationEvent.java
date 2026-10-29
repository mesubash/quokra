package io.github.mesubash.quokra.conversation;

import io.github.mesubash.quokra.conversation.Enums.EventType;
import io.github.mesubash.quokra.conversation.Enums.SenderType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "conversation_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversationEvent {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    private EventType type;

    @Enumerated(EnumType.STRING)
    private SenderType actorType;

    private String actorSub;
    private String actorName;
    private String reason;
    private Instant createdAt;

    static ConversationEvent of(UUID conversationId, EventType type, SenderType actorType, String actorSub,
                                String reason, Instant now) {
        ConversationEvent e = new ConversationEvent();
        e.conversationId = conversationId;
        e.type = type;
        e.actorType = actorType;
        e.actorSub = actorSub;
        e.reason = reason;
        e.createdAt = now;
        return e;
    }

    static ConversationEvent byAgent(UUID conversationId, EventType type, AgentRef agent, Instant now) {
        ConversationEvent e = of(conversationId, type, SenderType.AGENT, agent.sub(), null, now);
        e.actorName = agent.name();
        return e;
    }
}
