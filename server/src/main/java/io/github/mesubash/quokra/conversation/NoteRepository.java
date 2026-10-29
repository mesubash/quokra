package io.github.mesubash.quokra.conversation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface NoteRepository extends JpaRepository<ConversationNote, UUID> {

    List<ConversationNote> findByConversationIdOrderByIdAsc(UUID conversationId);
}
