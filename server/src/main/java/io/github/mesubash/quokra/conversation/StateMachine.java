package io.github.mesubash.quokra.conversation;

import static io.github.mesubash.quokra.conversation.Status.BOT;
import static io.github.mesubash.quokra.conversation.Status.HUMAN;
import static io.github.mesubash.quokra.conversation.Status.RESOLVED;
import static io.github.mesubash.quokra.conversation.Status.WAITING;

import io.github.mesubash.quokra.common.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;

// The only place that knows which status changes are allowed.
final class StateMachine {

    private record Key(Status from, Transition transition) {}

    private static final Map<Key, Status> ALLOWED = Map.of(
        new Key(BOT, Transition.HANDOFF), WAITING,
        new Key(WAITING, Transition.ACCEPT), HUMAN,
        new Key(WAITING, Transition.RESOLVE), RESOLVED,
        new Key(HUMAN, Transition.RESOLVE), RESOLVED,
        new Key(HUMAN, Transition.RETURN_TO_BOT), BOT,
        new Key(RESOLVED, Transition.REOPEN), BOT);

    private StateMachine() {}

    static Status next(Status from, Transition transition) {
        Status to = ALLOWED.get(new Key(from, transition));
        if (to == null) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION",
                "Can't " + transition.name().toLowerCase().replace('_', ' ') + " a conversation that is " + from);
        }
        return to;
    }
}
