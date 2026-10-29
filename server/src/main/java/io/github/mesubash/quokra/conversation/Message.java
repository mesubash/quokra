package io.github.mesubash.quokra.conversation;

import io.github.mesubash.quokra.conversation.Enums.SenderType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Message {

    public static final int MAX_BODY_LENGTH = 2000;

    // UUIDv7 ids sort by time, so "messages after X" is a plain id comparison.
    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    private SenderType senderType;

    private String senderSub;
    private String senderName;
    private String body;
    private String clientMessageId;
    private Instant createdAt;

    static Message create(UUID conversationId, SenderType senderType, String senderSub, String senderName,
                          String body, String clientMessageId, Instant now) {
        Message m = new Message();
        m.conversationId = conversationId;
        m.senderType = senderType;
        m.senderSub = senderSub;
        m.senderName = senderName;
        m.body = body;
        m.clientMessageId = clientMessageId;
        m.createdAt = now;
        return m;
    }
}
