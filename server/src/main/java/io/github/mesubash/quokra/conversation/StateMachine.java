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

    private static final Map<Key, Status> ALLOWED = Map.ofEntries(
        entry(BOT, Transition.HANDOFF, WAITING),
        entry(WAITING, Transition.ACCEPT, HUMAN),
        entry(WAITING, Transition.RESOLVE, RESOLVED),
        entry(HUMAN, Transition.RESOLVE, RESOLVED),
        entry(HUMAN, Transition.RETURN_TO_BOT, BOT),
        entry(HUMAN, Transition.TRANSFER_TO_QUEUE, WAITING),
        // Quiet chats close by themselves; waiting ones never do (it's the team's turn).
        entry(BOT, Transition.AUTO_RESOLVE, RESOLVED),
        entry(HUMAN, Transition.AUTO_RESOLVE, RESOLVED),
        // The customer writes again: the bot picks it up. An agent reopens it: it's theirs.
        entry(RESOLVED, Transition.REOPEN, BOT),
        entry(RESOLVED, Transition.AGENT_REOPEN, HUMAN),
        // The customer starts a new chat, whoever has this one.
        entry(BOT, Transition.CUSTOMER_END, RESOLVED),
        entry(WAITING, Transition.CUSTOMER_END, RESOLVED),
        entry(HUMAN, Transition.CUSTOMER_END, RESOLVED));

    private static Map.Entry<Key, Status> entry(Status from, Transition transition, Status to) {
        return Map.entry(new Key(from, transition), to);
    }

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
