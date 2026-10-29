package io.github.mesubash.quokra.conversation;

import java.time.Instant;
import java.util.UUID;

public record NoteView(UUID id, AgentRef author, String body, Instant createdAt) {

    public static NoteView of(ConversationNote n) {
        return new NoteView(n.getId(), new AgentRef(n.getAuthorSub(), n.getAuthorName()), n.getBody(), n.getCreatedAt());
    }
}
