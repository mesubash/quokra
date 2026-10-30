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

    static final Map<String, Status> ALLOWED = Map.ofEntries(
        Map.entry("BOT HANDOFF", Status.WAITING),
        Map.entry("WAITING ACCEPT", Status.HUMAN),
        Map.entry("WAITING RESOLVE", Status.RESOLVED),
        Map.entry("HUMAN RESOLVE", Status.RESOLVED),
        Map.entry("HUMAN RETURN_TO_BOT", Status.BOT),
        Map.entry("HUMAN TRANSFER_TO_QUEUE", Status.WAITING),
        Map.entry("BOT AUTO_RESOLVE", Status.RESOLVED),
        Map.entry("HUMAN AUTO_RESOLVE", Status.RESOLVED),
        Map.entry("RESOLVED REOPEN", Status.BOT),
        Map.entry("RESOLVED AGENT_REOPEN", Status.HUMAN),
        Map.entry("BOT CUSTOMER_END", Status.RESOLVED),
        Map.entry("WAITING CUSTOMER_END", Status.RESOLVED),
        Map.entry("HUMAN CUSTOMER_END", Status.RESOLVED));

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
