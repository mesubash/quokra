package io.github.mesubash.quokra.contact;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "contacts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Contact {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID tenantId;
    private String name;
    private String email;
    private Instant blockedAt;
    private String blockedBy;
    private Instant createdAt;
    private Instant updatedAt;

    public static Contact create(UUID tenantId, String name, String email, Instant now) {
        Contact c = new Contact();
        c.tenantId = tenantId;
        c.name = name;
        c.email = email;
        c.createdAt = now;
        c.updatedAt = now;
        return c;
    }

    public boolean isBlocked() {
        return blockedAt != null;
    }

    // A returning customer may give a new name; keep the latest one.
    public void rename(String newName, Instant now) {
        if (newName != null && !newName.equals(name)) {
            name = newName;
            updatedAt = now;
        }
    }

    public void block(String agentSub, Instant now) {
        blockedAt = now;
        blockedBy = agentSub;
        updatedAt = now;
    }

    public void unblock(Instant now) {
        blockedAt = null;
        blockedBy = null;
        updatedAt = now;
    }
}
