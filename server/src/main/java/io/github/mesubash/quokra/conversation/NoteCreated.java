package io.github.mesubash.quokra.conversation;

import java.util.UUID;

// Published after a note is stored; only agents receive it.
public record NoteCreated(UUID conversationId, NoteView note) {}
