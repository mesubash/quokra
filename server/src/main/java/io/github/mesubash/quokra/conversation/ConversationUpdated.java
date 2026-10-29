package io.github.mesubash.quokra.conversation;

import java.util.UUID;

// Published when a conversation's status changes; sent to clients once the transaction commits.
// Customers get `conversation`; agents watching the tenant's queue get `snapshot`.
public record ConversationUpdated(UUID conversationId, UUID tenantId, CustomerConversationView conversation,
                                  ConversationSnapshot snapshot) {}
