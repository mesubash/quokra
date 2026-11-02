package io.github.mesubash.quokra.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mesubash.quokra.TestcontainersConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// Search quality against the real provider: a sample handbook and questions with the part that should answer
// them, or none. Prints every score so quokra.ai.min-score can be chosen. Only runs with QUOKRA_AI_KEY set:
//   cd server && QUOKRA_AI_KEY=… ./mvnw test -Dtest=KnowledgeEvalTests
@EnabledIfEnvironmentVariable(named = "QUOKRA_AI_KEY", matches = ".+")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class KnowledgeEvalTests {

    @Autowired
    KnowledgeDocuments documents;

    @Autowired
    KnowledgeEmbedder embedder;

    @Autowired
    KnowledgeSearch search;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${quokra.ai.min-score}")
    double minScore;

    record Question(String text, String heading) {}

    @Test
    void theRightPartComesFirstAndOffTopicQuestionsScoreLow() throws Exception {
        UUID tenant = UUID.randomUUID();
        jdbc.update("insert into tenants (id, slug, display_name, publishable_key, bot_config) values (?, 'eval', 'Eval', 'pk_test_eval', 'test')",
            tenant);
        documents.add(tenant, "Handbook", read("handbook.md"), "eval");
        while (embedder.embedBatch() > 0) {
            // all parts
        }

        int found = 0;
        int answerable = 0;
        double lowestRight = 1;
        double highestOffTopic = 0;
        for (Question q : questions()) {
            List<KnowledgeSearch.Match> matches = search.search(tenant, q.text(), -1);
            KnowledgeSearch.Match best = matches.getFirst();
            String top3 = String.join(" | ", matches.stream().limit(3)
                .map(m -> m.heading() + String.format(" %.2f", m.score())).toList());
            if (q.heading() == null) {
                highestOffTopic = Math.max(highestOffTopic, best.score());
                System.out.printf("off topic  %-48s %s%n", q.text(), top3);
                continue;
            }
            answerable++;
            boolean inTop3 = matches.stream().limit(3).anyMatch(m -> q.heading().equals(m.heading()));
            if (inTop3) {
                found++;
                lowestRight = Math.min(lowestRight, matches.stream().filter(m -> q.heading().equals(m.heading()))
                    .findFirst().orElseThrow().score());
            }
            System.out.printf("%-10s %-48s %s%n", inTop3 ? "found" : "MISSED", q.text(), top3);
        }
        System.out.printf("%nfound in top 3: %d of %d; lowest right score %.2f; highest off-topic score %.2f%n",
            found, answerable, lowestRight, highestOffTopic);

        assertThat(found).as("answerable questions with the right part in the top 3").isGreaterThanOrEqualTo(answerable - 1);
        assertThat(lowestRight).as("right parts clear quokra.ai.min-score").isGreaterThanOrEqualTo(minScore);
        assertThat(highestOffTopic).as("off-topic questions stay under quokra.ai.min-score").isLessThan(minScore);
    }

    static String read(String name) throws IOException {
        return new ClassPathResource("knowledge-eval/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    static List<Question> questions() throws IOException {
        return read("questions.tsv").lines()
            .filter(l -> !l.isBlank() && !l.startsWith("#"))
            .map(l -> l.split("\t"))
            .map(p -> new Question(p[0], p[1].equals("-") ? null : p[1]))
            .toList();
    }
}
