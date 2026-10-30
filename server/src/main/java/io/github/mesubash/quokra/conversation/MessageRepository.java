package io.github.mesubash.quokra.conversation;

import io.github.mesubash.quokra.conversation.Enums.SenderType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface MessageRepository extends JpaRepository<Message, UUID> {

    Optional<Message> findByConversationIdAndClientMessageId(UUID conversationId, String clientMessageId);

    List<Message> findByConversationIdAndIdGreaterThanOrderByIdAsc(UUID conversationId, UUID after, Limit limit);

    @Modifying
    @Query("update Message m set m.conversationId = :to where m.conversationId = :from")
    int moveAll(UUID from, UUID to);

    // Who wrote the latest of these kinds of message, newest first.
    @Query("select m.senderType from Message m where m.conversationId = :conversationId and m.senderType in :types "
        + "order by m.id desc")
    List<SenderType> latestSenders(UUID conversationId, Collection<SenderType> types, Limit limit);

    // Newest first; callers reverse for display.
    @Query("select m from Message m where m.conversationId = :conversationId order by m.id desc")
    List<Message> findLatest(UUID conversationId, Limit limit);
}
