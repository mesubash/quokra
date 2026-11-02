package io.github.mesubash.quokra.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mesubash.quokra.TestcontainersConfiguration;
import io.github.mesubash.quokra.ai.AiClient;
import io.github.mesubash.quokra.ai.FakeAi;
import io.github.mesubash.quokra.security.DevAgentTokens;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;

// Embedding and search against a fake AI. Tests run in a transaction, so the scheduled embedder (its own
// transaction) never sees their rows; they call it directly instead.
@SpringBootTest(properties = {"quokra.dev.agent-tokens=true", "quokra.ai.key=test-key", "quokra.ai.min-score=0.3"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class KnowledgeSearchTests {

    static final FakeAi AI = new FakeAi();

    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry registry) {
        registry.add("quokra.ai.base-url", AI::baseUrl);
    }

    @Autowired
    KnowledgeDocuments documents;

    @Autowired
    KnowledgeEmbedder embedder;

    @Autowired
    KnowledgeSearch search;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DevAgentTokens devTokens;

    UUID acme;
    UUID globex;

    @BeforeEach
    void companies() {
        AI.failWith = 0;
        acme = tenant("acme");
        globex = tenant("globex");
        documents.add(acme, "Shipping", "# Shipping\nOrders ship within two working days.\n\n"
            + "# Returns\nReturn any item within thirty days for a refund.", "root");
        documents.add(acme, "Payments", "We accept cards and bank transfer. Invoices are emailed monthly.", "root");
        documents.add(globex, "Globex shipping", "Globex orders ship within two working days by sea.", "root");
    }

    @AfterEach
    void reset() {
        AI.failWith = 0;
    }

    UUID tenant(String slug) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into tenants (id, slug, display_name, publishable_key, bot_config) values (?, ?, ?, ?, 'test')",
            id, slug, slug, "pk_test_" + slug);
        return id;
    }

    int waiting() {
        return jdbc.queryForObject("select count(*) from knowledge_chunks where embedding is null", Integer.class);
    }

    @Test
    void embedsWaitingChunksAndFindsTheClosest() {
        assertThat(waiting()).isEqualTo(4);
        embedder.run();
        assertThat(waiting()).isZero();

        List<KnowledgeSearch.Match> matches = search.search(acme, "How many days until my refund for a return?");
        assertThat(matches).isNotEmpty();
        assertThat(matches.getFirst().heading()).isEqualTo("Returns");
        assertThat(matches.getFirst().title()).isEqualTo("Shipping");
        assertThat(matches).extracting(KnowledgeSearch.Match::score).isSortedAccordingTo((a, b) -> Double.compare(b, a));
    }

    @Test
    void onlyTheCompanysOwnDocuments() {
        embedder.run();
        assertThat(search.search(acme, "ship orders by sea")).extracting(KnowledgeSearch.Match::title)
            .doesNotContain("Globex shipping");
        assertThat(search.search(globex, "ship orders by sea")).extracting(KnowledgeSearch.Match::title)
            .containsExactly("Globex shipping");
    }

    @Test
    void anOffTopicQuestionFindsNothing() {
        embedder.run();
        assertThat(search.search(acme, "Write me a poem about the moon")).isEmpty();
    }

    @Test
    void aFailingProviderIsTriedAgainLater() {
        AI.failWith = 429;
        embedder.run();   // doesn't throw
        assertThat(waiting()).isEqualTo(4);

        AI.failWith = 0;
        embedder.run();
        assertThat(waiting()).isZero();

        AI.failWith = 500;
        assertThatThrownBy(() -> search.search(acme, "refund")).isInstanceOf(AiClient.AiException.class)
            .hasMessage("embeddings: HTTP 500");
    }

    @Test
    void editedDocumentsAndANewModelAreEmbeddedAgain() {
        embedder.run();
        UUID id = documents.list(acme).stream().filter(d -> d.title().equals("Payments")).findFirst().orElseThrow().id();
        documents.update(acme, id, "Payments", "Only cards now.", "root");
        assertThat(waiting()).isEqualTo(1);
        embedder.run();
        assertThat(waiting()).isZero();

        jdbc.update("update knowledge_chunks set embedding_model = 'an-old-model'");
        int before = AI.requests.get();
        embedder.run();
        assertThat(AI.requests.get()).isGreaterThan(before);
        assertThat(jdbc.queryForObject("select count(*) from knowledge_chunks where embedding_model = 'an-old-model'",
            Integer.class)).isZero();
    }

    @Test
    void adminsCanTryAQuestion() throws Exception {
        embedder.run();
        String root = devTokens.sign("root", "Root", List.of("QUOKRA_ADMIN"));

        var result = mvc.get().uri("/api/v1/admin/tenants/acme/knowledge/search").param("q", "refund for a return")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + root).exchange();
        assertThat(result).hasStatusOk().bodyJson().extractingPath("$.items[0].heading").isEqualTo("Returns");
        assertThat(result).bodyJson().extractingPath("$.items[0].match").isEqualTo(true);

        assertThat(mvc.get().uri("/api/v1/admin/tenants/acme/knowledge/search").param("q", " ")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + root)).hasStatus(HttpStatus.BAD_REQUEST);
        AI.failWith = 503;
        assertThat(mvc.get().uri("/api/v1/admin/tenants/acme/knowledge/search").param("q", "refund")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + root)).hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
            .bodyJson().extractingPath("$.error.code").isEqualTo("AI_UNAVAILABLE");
    }
}
