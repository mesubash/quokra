package io.github.mesubash.quokra.contact;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ContactRepository extends JpaRepository<Contact, UUID> {

    @Query("select c from Contact c where c.tenantId = :tenantId and lower(c.email) = lower(:email)")
    Optional<Contact> findByEmail(UUID tenantId, String email);
}
