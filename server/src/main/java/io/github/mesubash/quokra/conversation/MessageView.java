package io.github.mesubash.quokra.conversation;

import io.github.mesubash.quokra.bot.BotConfig.Option;
import io.github.mesubash.quokra.conversation.Enums.SenderType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// A message as customers and agents see it.
public record MessageView(UUID id, SenderType senderType, String senderName, String body,
                          List<Option> options, String clientMessageId, Instant createdAt) {

    public static MessageView of(Message m) {
        return new MessageView(m.getId(), m.getSenderType(), m.getSenderName(), m.getBody(), m.getOptions(),
            m.getClientMessageId(), m.getCreatedAt());
    }
}
