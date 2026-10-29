package io.github.mesubash.quokra.conversation;

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
        order by case c.priority
            when io.github.mesubash.quokra.conversation.Enums.Priority.URGENT then 0
            when io.github.mesubash.quokra.conversation.Enums.Priority.HIGH then 1
            else 2 end,
          c.waitingSince asc
        """)
    List<Conversation> findQueue(Collection<UUID> tenantIds, Limit limit);

    List<Conversation> findByTenantIdInAndStatusOrderByLastMessageAtDesc(
        Collection<UUID> tenantIds, Status status, Limit limit);

    List<Conversation> findByTenantIdInAndStatusAndAssigneeSubOrderByLastMessageAtDesc(
        Collection<UUID> tenantIds, Status status, String assigneeSub, Limit limit);
}
