package io.github.mesubash.quokra.conversation;

import io.github.mesubash.quokra.conversation.Enums.HandoffReason;
import io.github.mesubash.quokra.conversation.Enums.Priority;
import java.time.Instant;
import java.util.UUID;

// A conversation as agents see it in the live queue.
public record ConversationSnapshot(UUID id, UUID tenantId, Status status, Priority priority,
                                   HandoffReason handoffReason, String customerName, AgentRef assignee,
                                   Instant lastMessageAt, Instant waitingSince, Instant resolvedAt) {

    public static ConversationSnapshot of(Conversation c) {
        AgentRef assignee = c.getAssigneeSub() == null ? null : new AgentRef(c.getAssigneeSub(), c.getAssigneeName());
        return new ConversationSnapshot(c.getId(), c.getTenantId(), c.getStatus(), c.getPriority(), c.getHandoffReason(),
            c.getCustomerName(), assignee, c.getLastMessageAt(), c.getWaitingSince(), c.getResolvedAt());
    }
}
