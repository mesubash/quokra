package io.github.mesubash.quokra.conversation;

import io.github.mesubash.quokra.bot.BotConfig.Option;
import io.github.mesubash.quokra.conversation.Enums.SenderType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// A message as customers and agents see it. attachment is set for an image (fetched from its own URL).
public record MessageView(UUID id, SenderType senderType, String senderName, String body,
                          List<Option> options, String clientMessageId, Instant createdAt, Attachment attachment) {

    public record Attachment(String name, String contentType, int size) {}

    public static MessageView of(Message m) {
        Attachment attachment = m.getAttachmentKey() == null ? null
            : new Attachment(m.getAttachmentName(), m.getAttachmentType(), m.getAttachmentSize());
        return new MessageView(m.getId(), m.getSenderType(), m.getSenderName(), m.getBody(), m.getOptions(),
            m.getClientMessageId(), m.getCreatedAt(), attachment);
    }
}
