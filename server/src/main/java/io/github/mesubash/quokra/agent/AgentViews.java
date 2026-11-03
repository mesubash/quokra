package io.github.mesubash.quokra.agent;

import io.github.mesubash.quokra.conversation.Conversation;
import io.github.mesubash.quokra.conversation.Visit;
import io.github.mesubash.quokra.conversation.ConversationEvent;
import io.github.mesubash.quokra.conversation.Enums.EventType;
import io.github.mesubash.quokra.conversation.Enums.HandoffReason;
import io.github.mesubash.quokra.conversation.Enums.Priority;
import io.github.mesubash.quokra.conversation.Enums.SenderType;
import io.github.mesubash.quokra.conversation.Message;
import io.github.mesubash.quokra.conversation.Status;
import java.time.Instant;
import java.util.UUID;

// What agents see. Unlike the customer views, these include priority, reasons and people.
final class AgentViews {

    private AgentViews() {}

    record Person(String sub, String name) {}

    record Customer(String sub, UUID contactId, String name, String email, boolean blocked) {}

    record ConversationView(UUID id, String tenantSlug, Status status, Priority priority, HandoffReason handoffReason,
                            Customer customer, Person assignee, Instant createdAt, Instant lastMessageAt,
                            Instant waitingSince, Instant resolvedAt, Visit visit) {

        // visit: where the chat came from; null for chats from before it was recorded.
        static ConversationView of(Conversation c, String tenantSlug, boolean blocked) {
            return new ConversationView(c.getId(), tenantSlug, c.getStatus(), c.getPriority(), c.getHandoffReason(),
                customerOf(c, blocked), assigneeOf(c), c.getCreatedAt(), c.getLastMessageAt(), c.getWaitingSince(), c.getResolvedAt(),
                c.getVisit());
        }
    }

    record Summary(UUID id, String tenantSlug, Status status, Priority priority, HandoffReason handoffReason,
                   String customerName, String lastMessagePreview, Instant lastMessageAt, Instant waitingSince,
                   Person assignee, int unreadCount, Boolean rating) {

        static final int PREVIEW_LENGTH = 120;

        // rating: the customer's thumb (true: up) when the chat was rated, else null.
        static Summary of(Conversation c, String tenantSlug, Message last, int unreadCount, Boolean rating) {
            String preview = last == null ? "" : last.getBody();
            if (preview.length() > PREVIEW_LENGTH) {
                preview = preview.substring(0, PREVIEW_LENGTH);
            }
            return new Summary(c.getId(), tenantSlug, c.getStatus(), c.getPriority(), c.getHandoffReason(),
                c.getCustomerName(), preview, c.getLastMessageAt(), c.getWaitingSince(), assigneeOf(c), unreadCount, rating);
        }
    }

    record EventView(UUID id, EventType type, SenderType actorType, String actorName, String reason, Instant createdAt) {

        static EventView of(ConversationEvent e) {
            return new EventView(e.getId(), e.getType(), e.getActorType(), e.getActorName(), e.getReason(), e.getCreatedAt());
        }
    }

    private static Customer customerOf(Conversation c, boolean blocked) {
        if (c.getCustomerSub() == null && c.getContactId() == null) {
            return null;
        }
        return new Customer(c.getCustomerSub(), c.getContactId(), c.getCustomerName(), c.getCustomerEmail(), blocked);
    }

    private static Person assigneeOf(Conversation c) {
        return c.getAssigneeSub() == null ? null : new Person(c.getAssigneeSub(), c.getAssigneeName());
    }
}
