package io.github.mesubash.quokra.conversation;

// A logged-in customer, as vouched for by the tenant's backend.
public record CustomerIdentity(String sub, String name, String email) {}
