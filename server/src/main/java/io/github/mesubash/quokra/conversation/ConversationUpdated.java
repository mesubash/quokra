package io.github.mesubash.quokra.conversation;

import java.util.UUID;

// Published when a conversation's status changes; sent to clients once the transaction commits.
public record ConversationUpdated(UUID conversationId, CustomerConversationView conversation) {}
