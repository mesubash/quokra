package io.github.mesubash.quokra.conversation;

import java.util.UUID;

// Published after a note is stored; only agents receive it.
public record NoteCreated(UUID conversationId, UUID tenantId, String assigneeSub, String customerName, NoteView note) {}
