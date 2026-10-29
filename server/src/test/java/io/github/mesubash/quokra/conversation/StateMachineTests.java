package io.github.mesubash.quokra.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mesubash.quokra.common.ApiException;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StateMachineTests {

    static final Map<String, Status> ALLOWED = Map.of(
        "BOT HANDOFF", Status.WAITING,
        "WAITING ACCEPT", Status.HUMAN,
        "WAITING RESOLVE", Status.RESOLVED,
        "HUMAN RESOLVE", Status.RESOLVED,
        "HUMAN RETURN_TO_BOT", Status.BOT,
        "HUMAN TRANSFER_TO_QUEUE", Status.WAITING,
        "RESOLVED REOPEN", Status.BOT,
        "RESOLVED AGENT_REOPEN", Status.HUMAN);

    static Stream<Arguments> everyCombination() {
        return Stream.of(Status.values())
            .flatMap(from -> Stream.of(Transition.values()).map(t -> Arguments.of(from, t)));
    }

    @ParameterizedTest(name = "{0} + {1}")
    @MethodSource("everyCombination")
    void allowsOnlyTheDocumentedTransitions(Status from, Transition transition) {
        Status expected = ALLOWED.get(from + " " + transition);

        if (expected != null) {
            assertThat(StateMachine.next(from, transition)).isEqualTo(expected);
        } else {
            assertThatThrownBy(() -> StateMachine.next(from, transition))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INVALID_TRANSITION"));
        }
    }
}
