package io.github.mesubash.quokra.conversation;

import java.util.UUID;

// Published when an agent hands a conversation to a colleague, so the colleague is notified.
public record ConversationTransferred(UUID conversationId, UUID tenantId, AgentRef to, String byName,
                                      String customerName) {}
