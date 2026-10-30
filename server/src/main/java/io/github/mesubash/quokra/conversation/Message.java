package io.github.mesubash.quokra.conversation;

import io.github.mesubash.quokra.bot.BotConfig.Option;
import io.github.mesubash.quokra.conversation.Enums.SenderType;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
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
    // The text of an image message, so previews, notifications and searches read sensibly.
    public static final String IMAGE_TEXT = "Sent an image";

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

    @Convert(converter = OptionsConverter.class)
    private List<Option> options;

    private Instant createdAt;

    // Set for an image message; the file is in the FileStore under attachmentKey.
    private String attachmentKey;
    private String attachmentType;
    private Integer attachmentSize;
    private String attachmentName;

    // The quoted message, copied when this one was sent.
    private UUID replyToId;
    @Enumerated(EnumType.STRING)
    private SenderType replyToSender;
    private String replyToName;
    private String replyToPreview;

    static Message create(UUID conversationId, SenderType senderType, String senderSub, String senderName,
                          String body, String clientMessageId, List<Option> options, Instant now) {
        Message m = new Message();
        m.conversationId = conversationId;
        m.senderType = senderType;
        m.senderSub = senderSub;
        m.senderName = senderName;
        m.body = body;
        m.clientMessageId = clientMessageId;
        m.options = options;
        m.createdAt = now;
        return m;
    }

    static Message image(UUID conversationId, SenderType senderType, String senderSub, String senderName,
                         String clientMessageId, Attachment attachment, Instant now) {
        Message m = create(conversationId, senderType, senderSub, senderName, IMAGE_TEXT, clientMessageId, null, now);
        m.attachmentKey = attachment.key();
        m.attachmentType = attachment.contentType();
        m.attachmentSize = attachment.size();
        m.attachmentName = attachment.name();
        return m;
    }

    void quote(Quote quote) {
        if (quote == null) {
            return;
        }
        replyToId = quote.id();
        replyToSender = quote.senderType();
        replyToName = quote.senderName();
        replyToPreview = quote.preview();
    }

    // What a reply shows of the message it answers.
    public record Quote(UUID id, SenderType senderType, String senderName, String preview) {}

    // An image already in the file store.
    public record Attachment(String key, String contentType, int size, String name) {}
}
