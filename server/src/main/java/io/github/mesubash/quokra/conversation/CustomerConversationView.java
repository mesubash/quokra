package io.github.mesubash.quokra.conversation;

import java.time.Instant;
import java.util.UUID;

// A conversation as the customer sees it: no priority, handoff reason or agent ids.
// asking: the bot is asking for the customer's EMAIL or NAME before connecting them to the team.
public record CustomerConversationView(UUID id, Status status, Assignee assignee, Asking asking,
                                       Instant createdAt, Instant lastMessageAt, Instant resolvedAt) {

    public record Assignee(String name) {}

    public enum Asking { EMAIL, NAME }

    public static CustomerConversationView of(Conversation c) {
        Assignee assignee = c.getAssigneeName() == null ? null : new Assignee(c.getAssigneeName());
        Asking asking = c.getPendingHandoff() == null ? null : c.getCustomerEmail() == null ? Asking.EMAIL : Asking.NAME;
        return new CustomerConversationView(c.getId(), c.getStatus(), assignee, asking,
            c.getCreatedAt(), c.getLastMessageAt(), c.getResolvedAt());
    }
}
