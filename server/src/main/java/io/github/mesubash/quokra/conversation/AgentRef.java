package io.github.mesubash.quokra.conversation;

// The agent doing something, as known from their login token.
public record AgentRef(String sub, String name) {}
