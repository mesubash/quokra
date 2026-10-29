package io.github.mesubash.quokra.conversation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface MessageRepository extends JpaRepository<Message, UUID> {

    Optional<Message> findByConversationIdAndClientMessageId(UUID conversationId, String clientMessageId);

    List<Message> findByConversationIdAndIdGreaterThanOrderByIdAsc(UUID conversationId, UUID after, Limit limit);

    // Newest first; callers reverse for display.
    @Query("select m from Message m where m.conversationId = :conversationId order by m.id desc")
    List<Message> findLatest(UUID conversationId, Limit limit);
}
