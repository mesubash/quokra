package io.github.mesubash.quokra.conversation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    Optional<Conversation> findFirstByTenantIdAndVisitorIdOrderByCreatedAtDesc(UUID tenantId, UUID visitorId);
}
