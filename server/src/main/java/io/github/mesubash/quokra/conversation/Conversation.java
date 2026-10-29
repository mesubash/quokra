package io.github.mesubash.quokra.conversation;

import io.github.mesubash.quokra.conversation.Enums.HandoffReason;
import io.github.mesubash.quokra.conversation.Enums.Priority;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "conversations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Conversation {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    private Status status;

    private UUID visitorId;

    private String customerSub;
    private String customerName;
    private String customerEmail;

    private UUID contactId;

    // The customer asked for a person; we're waiting for their contact details first.
    @Enumerated(EnumType.STRING)
    private HandoffReason pendingHandoff;

    private String channel;

    private String assigneeSub;
    private String assigneeName;

    @Enumerated(EnumType.STRING)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    private HandoffReason handoffReason;

    private int fallbackCount;

    @Version
    private int version;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastMessageAt;
    private Instant waitingSince;
    private Instant resolvedAt;

    static Conversation start(UUID tenantId, UUID visitorId, Instant now) {
        Conversation c = new Conversation();
        c.tenantId = tenantId;
        c.visitorId = visitorId;
        c.status = Status.BOT;
        c.channel = "WEB";
        c.priority = Priority.NORMAL;
        c.createdAt = now;
        c.updatedAt = now;
        c.lastMessageAt = now;
        return c;
    }

    public boolean isOpen() {
        return status != Status.RESOLVED;
    }

    void moveTo(Status next, Instant now) {
        status = next;
        pendingHandoff = null;
        updatedAt = now;
        resolvedAt = next == Status.RESOLVED ? now : null;
        waitingSince = next == Status.WAITING ? now : null;
    }

    boolean isAssignedTo(String agentSub) {
        return agentSub != null && agentSub.equals(assigneeSub);
    }

    void assign(String agentSub, String agentName) {
        assigneeSub = agentSub;
        assigneeName = agentName;
    }

    void unassign() {
        assigneeSub = null;
        assigneeName = null;
        handoffReason = null;
        priority = Priority.NORMAL;
        fallbackCount = 0;
    }

    void handOff(HandoffReason reason, Instant now) {
        handoffReason = reason;
        priority = reason == HandoffReason.URGENT ? Priority.URGENT : Priority.NORMAL;
        updatedAt = now;
    }

    void reopen(Instant now) {
        handoffReason = null;
        priority = Priority.NORMAL;
        fallbackCount = 0;
        assigneeSub = null;
        assigneeName = null;
        updatedAt = now;
    }

    // Guests leave their details before talking to a person; identified customers already have them.
    boolean needsContact() {
        return contactId == null && customerSub == null;
    }

    void awaitContact(HandoffReason reason, Instant now) {
        pendingHandoff = reason;
        updatedAt = now;
    }

    // Kept until the customer has also answered the name question.
    void rememberEmail(String email, Instant now) {
        customerEmail = email;
        updatedAt = now;
    }

    void linkContact(UUID contact, String name, String email, Instant now) {
        contactId = contact;
        customerName = name;
        customerEmail = email;
        updatedAt = now;
    }

    void countFallback() {
        fallbackCount++;
    }

    void resetFallbacks() {
        fallbackCount = 0;
    }

    void touch(Instant now) {
        lastMessageAt = now;
        updatedAt = now;
    }
}
