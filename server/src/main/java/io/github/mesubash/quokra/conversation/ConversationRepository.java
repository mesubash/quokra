package io.github.mesubash.quokra.conversation;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    Optional<Conversation> findFirstByTenantIdAndVisitorIdOrderByCreatedAtDesc(UUID tenantId, UUID visitorId);

    // Urgent first, then whoever has waited longest.
    @Query("""
        select c from Conversation c
        where c.tenantId in :tenantIds and c.status = io.github.mesubash.quokra.conversation.Status.WAITING
        order by c.priorityRank, c.waitingSince, c.id
        """)
    List<Conversation> findQueue(Collection<UUID> tenantIds, Limit limit);

    @Query("""
        select c from Conversation c
        where c.tenantId in :tenantIds and c.status = io.github.mesubash.quokra.conversation.Status.WAITING
          and (c.priorityRank > :rank or (c.priorityRank = :rank
            and (c.waitingSince > :at or (c.waitingSince = :at and c.id > :id))))
        order by c.priorityRank, c.waitingSince, c.id
        """)
    List<Conversation> findQueueAfter(Collection<UUID> tenantIds, int rank, Instant at, UUID id, Limit limit);

    // Most recent first. assigneeSub null means anyone's.
    @Query("""
        select c from Conversation c
        where c.tenantId in :tenantIds and c.status = :status
          and (:assigneeSub is null or c.assigneeSub = :assigneeSub)
        order by c.lastMessageAt desc, c.id desc
        """)
    List<Conversation> findRecent(Collection<UUID> tenantIds, Status status, String assigneeSub, Limit limit);

    @Query("""
        select c from Conversation c
        where c.tenantId in :tenantIds and c.status = :status
          and (:assigneeSub is null or c.assigneeSub = :assigneeSub)
          and (c.lastMessageAt < :at or (c.lastMessageAt = :at and c.id < :id))
        order by c.lastMessageAt desc, c.id desc
        """)
    List<Conversation> findRecentAfter(Collection<UUID> tenantIds, Status status, String assigneeSub, Instant at, UUID id,
                                       Limit limit);
}
