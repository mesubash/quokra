package io.github.mesubash.quokra.bot;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mesubash.quokra.bot.BotConfig.Human;
import io.github.mesubash.quokra.bot.BotConfig.MenuItem;
import io.github.mesubash.quokra.bot.BotConfig.Urgent;
import io.github.mesubash.quokra.bot.BotEngine.Answer;
import io.github.mesubash.quokra.bot.BotEngine.Decision;
import io.github.mesubash.quokra.bot.BotEngine.Fallback;
import io.github.mesubash.quokra.bot.BotEngine.Handoff;
import io.github.mesubash.quokra.bot.BotEngine.Reason;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BotEngineTests {

    static final BotConfig CONFIG = new BotConfig(
        "Hi",
        null,
        List.of(
            new MenuItem("pricing", "Pricing", List.of("price", "cost", "plan"), "Pricing answer"),
            new MenuItem("account", "Account", List.of("password", "sign in", "plan"), "Account answer")),
        new Human("Talk to a person", List.of("agent", "human")),
        new Urgent(List.of("urgent", "payment failed"), "Urgent answer"),
        "Fallback answer",
        2,
        "Handoff answer");

    @ParameterizedTest(name = "\"{0}\" / option {1} -> {2}")
    @CsvSource(nullValues = "-", textBlock = """
        What does it cost?,           -,       Answer,   Pricing answer
        COST,                         -,       Answer,   Pricing answer
        I forgot my password,         -,       Answer,   Account answer
        how do I sign in,             -,       Answer,   Account answer
        -,                            pricing, Answer,   Pricing answer
        -,                            account, Answer,   Account answer
        my plan and password,         -,       Answer,   Account answer
        which plan,                   -,       Answer,   Pricing answer
        can I talk to an agent,       -,       Handoff,  Handoff answer
        -,                            human,   Handoff,  Handoff answer
        it's URGENT,                  -,       Handoff,  Urgent answer
        my payment failed,            -,       Handoff,  Urgent answer
        urgent cost question,         pricing, Handoff,  Urgent answer
        hello there,                  -,       Fallback, Fallback answer
        -,                            nope,    Fallback, Fallback answer
        passwords,                    -,       Fallback, Fallback answer
        signing in,                   -,       Fallback, Fallback answer
        """)
    void decides(String text, String optionId, String kind, String reply) {
        Decision decision = BotEngine.decide(CONFIG, text, optionId, 0);

        assertThat(decision.getClass().getSimpleName()).isEqualTo(kind);
        assertThat(text(decision)).isEqualTo(reply);
    }

    @Test
    void urgentHandoffHasUrgentReason() {
        assertThat(BotEngine.decide(CONFIG, "urgent", null, 0)).isEqualTo(new Handoff(Reason.URGENT, "Urgent answer"));
    }

    @Test
    void handsOffAfterTooManyMisses() {
        assertThat(BotEngine.decide(CONFIG, "hello", null, 1))
            .isEqualTo(new Handoff(Reason.FALLBACK_LIMIT, "Handoff answer"));
    }

    @Test
    void accentsAndPunctuationAreIgnored() {
        assertThat(BotEngine.words("Ça coûte combien?!")).isEqualTo(" ca coute combien ");
    }

    private static String text(Decision decision) {
        return switch (decision) {
            case Answer a -> a.text();
            case Fallback f -> f.text();
            case Handoff h -> h.text();
        };
    }
}
