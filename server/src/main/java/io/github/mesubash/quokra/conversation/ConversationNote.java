package io.github.mesubash.quokra.conversation;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

// An agent-only note on a conversation.
@Entity
@Table(name = "conversation_notes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversationNote {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID conversationId;
    private String authorSub;
    private String authorName;
    private String body;
    private Instant createdAt;

    static ConversationNote create(UUID conversationId, AgentRef author, String body, Instant now) {
        ConversationNote n = new ConversationNote();
        n.conversationId = conversationId;
        n.authorSub = author.sub();
        n.authorName = author.name();
        n.body = body;
        n.createdAt = now;
        return n;
    }
}
