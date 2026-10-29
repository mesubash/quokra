package io.github.mesubash.quokra.conversation;

import io.github.mesubash.quokra.common.ApiException;
import io.github.mesubash.quokra.conversation.Enums.Priority;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;

// Where the next page of a conversation list starts: the sort values of the last row shown.
// Opaque to clients; they pass back whatever nextCursor they got.
public record PageCursor(int rank, Instant at, UUID id) {

    public static PageCursor after(Conversation c) {
        Instant at = c.getStatus() == Status.WAITING ? c.getWaitingSince() : c.getLastMessageAt();
        // The database keeps microseconds; a finer value would no longer match the row it came from.
        return new PageCursor(rank(c.getPriority()), at.truncatedTo(ChronoUnit.MICROS), c.getId());
    }

    // Same as the priority_rank column (V7). Worked out here because a just-changed entity hasn't read it back yet.
    static int rank(Priority priority) {
        return switch (priority) {
            case URGENT -> 0;
            case HIGH -> 1;
            case NORMAL -> 2;
        };
    }

    public String encode() {
        String raw = rank + "|" + at + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static PageCursor decode(String cursor) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
            return new PageCursor(Integer.parseInt(parts[0]), Instant.parse(parts[1]), UUID.fromString(parts[2]));
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid cursor");
        }
    }
}
