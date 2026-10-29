package io.github.mesubash.quokra.conversation;

import java.time.Instant;
import java.util.UUID;

// A conversation as the customer sees it: no priority, handoff reason or agent ids.
public record CustomerConversationView(UUID id, Status status, Assignee assignee, Instant createdAt,
                                       Instant lastMessageAt, Instant resolvedAt) {

    public record Assignee(String name) {}

    public static CustomerConversationView of(Conversation c) {
        Assignee assignee = c.getAssigneeName() == null ? null : new Assignee(c.getAssigneeName());
        return new CustomerConversationView(c.getId(), c.getStatus(), assignee, c.getCreatedAt(), c.getLastMessageAt(),
            c.getResolvedAt());
    }
}
