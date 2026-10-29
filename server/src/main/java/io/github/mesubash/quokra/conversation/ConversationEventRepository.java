package io.github.mesubash.quokra.conversation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ConversationEventRepository extends JpaRepository<ConversationEvent, UUID> {

    List<ConversationEvent> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);
}
