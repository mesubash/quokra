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

    Optional<Conversation> findFirstByTenantIdAndCustomerSubOrderByCreatedAtDesc(UUID tenantId, String customerSub);

    // Urgent first, then whoever has waited longest.
    @Query("""
        select c from Conversation c
        where c.tenantId in :tenantIds and c.status = io.github.mesubash.quokra.conversation.Status.WAITING
          and (:q is null or lower(coalesce(c.customerName, '')) like :q escape '\\'
            or lower(coalesce(c.customerEmail, '')) like :q escape '\\'
            or exists (select m.id from Message m where m.conversationId = c.id and lower(m.body) like :q escape '\\'))
        order by c.priorityRank, c.waitingSince, c.id
        """)
    List<Conversation> findQueue(Collection<UUID> tenantIds, String q, Limit limit);

    @Query("""
        select c from Conversation c
        where c.tenantId in :tenantIds and c.status = io.github.mesubash.quokra.conversation.Status.WAITING
          and (c.priorityRank > :rank or (c.priorityRank = :rank
            and (c.waitingSince > :at or (c.waitingSince = :at and c.id > :id))))
          and (:q is null or lower(coalesce(c.customerName, '')) like :q escape '\\'
            or lower(coalesce(c.customerEmail, '')) like :q escape '\\'
            or exists (select m.id from Message m where m.conversationId = c.id and lower(m.body) like :q escape '\\'))
        order by c.priorityRank, c.waitingSince, c.id
        """)
    List<Conversation> findQueueAfter(Collection<UUID> tenantIds, int rank, Instant at, UUID id, String q, Limit limit);

    // The same person's other conversations with this tenant: same contact (email) or same logged-in customer.
    @Query("""
        select c from Conversation c
        where c.tenantId = :tenantId and c.id <> :exclude
          and ((:contactId is not null and c.contactId = :contactId)
            or (:customerSub is not null and c.customerSub = :customerSub))
        order by c.createdAt desc
        """)
    List<Conversation> findHistory(UUID tenantId, UUID exclude, UUID contactId, String customerSub, Limit limit);

    long countByTenantIdInAndStatus(Collection<UUID> tenantIds, Status status);

    long countByTenantIdInAndStatusAndAssigneeSub(Collection<UUID> tenantIds, Status status, String assigneeSub);

    // Most recent first. assigneeSub null means anyone's.
    @Query("""
        select c from Conversation c
        where c.tenantId in :tenantIds and c.status = :status
          and (:assigneeSub is null or c.assigneeSub = :assigneeSub)
          and (:q is null or lower(coalesce(c.customerName, '')) like :q escape '\\'
            or lower(coalesce(c.customerEmail, '')) like :q escape '\\'
            or exists (select m.id from Message m where m.conversationId = c.id and lower(m.body) like :q escape '\\'))
        order by c.lastMessageAt desc, c.id desc
        """)
    List<Conversation> findRecent(Collection<UUID> tenantIds, Status status, String assigneeSub, String q, Limit limit);

    @Query("""
        select c from Conversation c
        where c.tenantId in :tenantIds and c.status = :status
          and (:assigneeSub is null or c.assigneeSub = :assigneeSub)
          and (c.lastMessageAt < :at or (c.lastMessageAt = :at and c.id < :id))
          and (:q is null or lower(coalesce(c.customerName, '')) like :q escape '\\'
            or lower(coalesce(c.customerEmail, '')) like :q escape '\\'
            or exists (select m.id from Message m where m.conversationId = c.id and lower(m.body) like :q escape '\\'))
        order by c.lastMessageAt desc, c.id desc
        """)
    List<Conversation> findRecentAfter(Collection<UUID> tenantIds, Status status, String assigneeSub, Instant at, UUID id,
                                       String q, Limit limit);
}
