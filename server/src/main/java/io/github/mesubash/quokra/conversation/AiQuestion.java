package io.github.mesubash.quokra.conversation;

import java.util.UUID;

// A typed question the bot couldn't match, for the AI to try once the message is saved (v3 step 3b).
// ConversationService.finishAiQuestion then answers it, or carries on as a normal miss.
public record AiQuestion(UUID conversationId, UUID messageId) {}
