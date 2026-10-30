package io.github.mesubash.quokra.conversation;

import java.util.UUID;

// Published after a message is stored; sent to clients once the transaction commits.
public record MessageCreated(UUID conversationId, UUID tenantId, MessageView message) {}
