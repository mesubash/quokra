package io.github.mesubash.quokra.conversation;

import java.util.UUID;

// Published when a chat joins the queue, for the AI's summary for the agent (v3 step 6).
public record HandedOff(UUID conversationId) {}
