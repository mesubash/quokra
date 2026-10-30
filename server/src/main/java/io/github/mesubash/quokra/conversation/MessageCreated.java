package io.github.mesubash.quokra.conversation;

import java.util.UUID;

// Published after a message is stored; sent to clients once the transaction commits.
// assigneeSub and customerName let the console notify the right agent without asking the server.
public record MessageCreated(UUID conversationId, UUID tenantId, String assigneeSub, String customerName,
                             MessageView message) {}
