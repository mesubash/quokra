package io.github.mesubash.quokra.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

class BotConfigParserTests {

    static final String VALID = """
        greeting: Hi
        menu:
          - id: pricing
            title: Pricing
            keywords: [price]
            answer: Pricing answer
        human:
          title: Talk to a person
          keywords: [agent]
        urgent:
          keywords: [urgent]
          message: Urgent answer
        fallback: Fallback answer
        max_fallbacks: 2
        handoff_message: Handoff answer
        """;

    @Test
    void parsesAValidFile() {
        BotConfig config = BotConfigParser.parse("valid.yaml", VALID);

        assertThat(config.greeting()).isEqualTo("Hi");
        assertThat(config.menu()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo("pricing");
            assertThat(item.keywords()).containsExactly("price");
        });
        assertThat(config.options()).extracting(BotConfig.Option::id).containsExactly("pricing", "human");
        assertThat(config.maxFallbacks()).isEqualTo(2);
    }

    @Test
    void teaserIsOptional() {
        assertThat(BotConfigParser.parse("valid.yaml", VALID).teaser()).isNull();
        assertThat(BotConfigParser.parse("valid.yaml", "teaser: Need help?\n" + VALID).teaser()).isEqualTo("Need help?");
    }

    @Test
    void builtInDemoBotIsValid() throws IOException {
        String yaml = new ClassPathResource("bots/demo.yml").getContentAsString(StandardCharsets.UTF_8);

        assertThat(BotConfigParser.parse("demo.yml", yaml).menu()).isNotEmpty();
    }

    @Test
    void missingFieldIsNamed() {
        assertThatThrownBy(() -> BotConfigParser.parse("bad.yaml", VALID.replace("greeting: Hi\n", "")))
            .hasMessage("bad.yaml: greeting is required");
    }

    @Test
    void missingAnswerNamesTheMenuItem() {
        assertThatThrownBy(() -> BotConfigParser.parse("bad.yaml", VALID.replace("    answer: Pricing answer\n", "")))
            .hasMessage("bad.yaml: menu[pricing].answer is required");
    }

    @Test
    void reservedMenuIdIsRejected() {
        assertThatThrownBy(() -> BotConfigParser.parse("bad.yaml", VALID.replace("id: pricing", "id: human")))
            .hasMessage("bad.yaml: menu id 'human' is reserved");
    }

    @Test
    void duplicateMenuIdIsRejected() {
        String twice = VALID.replace("human:\n", """
              - id: pricing
                title: Again
                keywords: [x]
                answer: Again
            human:
            """);

        assertThatThrownBy(() -> BotConfigParser.parse("bad.yaml", twice))
            .hasMessage("bad.yaml: menu id 'pricing' is used twice");
    }

    @Test
    void maxFallbacksMustBePositive() {
        assertThatThrownBy(() -> BotConfigParser.parse("bad.yaml", VALID.replace("max_fallbacks: 2", "max_fallbacks: 0")))
            .hasMessage("bad.yaml: max_fallbacks must be a number of at least 1");
    }

    @Test
    void brokenYamlIsReported() {
        assertThatThrownBy(() -> BotConfigParser.parse("bad.yaml", "menu: [unclosed"))
            .hasMessageStartingWith("bad.yaml: not valid YAML");
    }

    @Test
    void registryFolderReplacesBuiltInBot(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("demo.yml"), VALID);

        BotConfigRegistry registry = new BotConfigRegistry(dir.toString());

        assertThat(registry.find("demo")).get().extracting(BotConfig::greeting).isEqualTo("Hi");
        assertThat(registry.find("missing")).isEmpty();
    }
}
