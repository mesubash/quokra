package io.github.mesubash.quokra.ai;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mesubash.quokra.ai.ReplySuggester.Reason;
import io.github.mesubash.quokra.ai.ReplySuggester.Source;
import io.github.mesubash.quokra.ai.ReplySuggester.Suggestion;
import io.github.mesubash.quokra.knowledge.KnowledgeSearch.Match;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// The checks on what the model answers; a suggestion only gets through if it passes all of them.
class ReplySuggesterTests {

    static final List<Match> PASSAGES = List.of(
        new Match(UUID.randomUUID(), "Handbook", 0, "Returns", "Return items within 30 days.", 0.7),
        new Match(UUID.randomUUID(), "Handbook", 1, "Help", "See https://help.example.test/returns for the form.", 0.6));

    static Suggestion check(String raw) {
        return ReplySuggester.check(raw, PASSAGES);
    }

    @Test
    void keepsAnAnswerThatCitesItsPassages() {
        Suggestion s = check("{\"answer\": \"You can return items within 30 days.\", \"sources\": [1]}");
        assertThat(s.reason()).isNull();
        assertThat(s.text()).isEqualTo("You can return items within 30 days.");
        assertThat(s.sources()).containsExactly(new Source("Handbook", "Returns"));
    }

    @Test
    void takesSourceNumbersWrittenAsText() {
        assertThat(check("{\"answer\": \"Within 30 days.\", \"sources\": [\"1\"]}").reason()).isNull();
    }

    @Test
    void dropsNoneAndEmptyAnswers() {
        assertThat(check("{\"answer\": \"NONE\", \"sources\": []}").reason()).isEqualTo(Reason.NO_ANSWER);
        assertThat(check("{\"answer\": \"none.\", \"sources\": [1]}").reason()).isEqualTo(Reason.NO_ANSWER);
        assertThat(check("{\"answer\": \"  \", \"sources\": [1]}").reason()).isEqualTo(Reason.NO_ANSWER);
    }

    @Test
    void dropsAnswersWithoutValidSources() {
        assertThat(check("{\"answer\": \"Within 30 days.\", \"sources\": []}").reason()).isEqualTo(Reason.NO_ANSWER);
        assertThat(check("{\"answer\": \"Within 30 days.\"}").reason()).isEqualTo(Reason.NO_ANSWER);
        assertThat(check("{\"answer\": \"Within 30 days.\", \"sources\": [3]}").reason()).isEqualTo(Reason.NO_ANSWER);
        assertThat(check("{\"answer\": \"Within 30 days.\", \"sources\": [\"all\"]}").reason()).isEqualTo(Reason.NO_ANSWER);
    }

    @Test
    void linksOnlyFromThePassagesItUsed() {
        assertThat(check("{\"answer\": \"Use https://help.example.test/returns.\", \"sources\": [2]}").reason()).isNull();
        // Right link, but not from a passage it cited.
        assertThat(check("{\"answer\": \"Use https://help.example.test/returns.\", \"sources\": [1]}").reason())
            .isEqualTo(Reason.NO_ANSWER);
        assertThat(check("{\"answer\": \"Pay at www.other.test now.\", \"sources\": [1]}").reason()).isEqualTo(Reason.NO_ANSWER);
    }

    @Test
    void plainTextOfAReasonableLength() {
        assertThat(check("{\"answer\": \"<b>Within</b> 30 days.\", \"sources\": [1]}").reason()).isEqualTo(Reason.NO_ANSWER);
        assertThat(check("{\"answer\": \"" + "a".repeat(ReplySuggester.MAX_LENGTH + 1) + "\", \"sources\": [1]}").reason())
            .isEqualTo(Reason.NO_ANSWER);
        assertThat(check("Sure! Returns take 30 days.").reason()).isEqualTo(Reason.NO_ANSWER);
    }
}
