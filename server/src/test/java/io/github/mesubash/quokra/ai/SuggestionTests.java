package io.github.mesubash.quokra.ai;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mesubash.quokra.TestcontainersConfiguration;
import io.github.mesubash.quokra.conversation.ConversationService;
import io.github.mesubash.quokra.conversation.Enums.HandoffReason;
import io.github.mesubash.quokra.conversation.Enums.SenderType;
import io.github.mesubash.quokra.knowledge.KnowledgeDocuments;
import io.github.mesubash.quokra.knowledge.KnowledgeEmbedder;
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
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

// POST /agent/conversations/{id}/suggestion against a fake AI.
@SpringBootTest(properties = {"quokra.dev.agent-tokens=true", "quokra.ai.key=test-key", "quokra.ai.min-score=0.3"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class SuggestionTests {

    static final FakeAi AI = new FakeAi();

    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry registry) {
        registry.add("quokra.ai.base-url", AI::baseUrl);
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ConversationService service;

    @Autowired
    KnowledgeDocuments documents;

    @Autowired
    KnowledgeEmbedder embedder;

    @Autowired
    DevAgentTokens devTokens;

    UUID acme = UUID.randomUUID();
    String alice;
    String carol;

    @BeforeEach
    void setUp() throws Exception {
        AI.failWith = 0;
        AI.chatReply = "{\"answer\": \"You can return any item within thirty days.\", \"sources\": [1]}";
        jdbc.update("""
            insert into tenants (id, slug, display_name, publishable_key, bot_config, ai_enabled)
            values (?, 'acme', 'Acme', 'pk_test_acme', 'test', true),
                   (gen_random_uuid(), 'globex', 'Globex', 'pk_test_globex', 'test', true)
            """, acme);
        jdbc.update("insert into tenant_agents (tenant_id, agent_sub) values (?, 'alice')", acme);
        jdbc.update("insert into tenant_agents (tenant_id, agent_sub) select id, 'carol' from tenants where slug = 'globex'");
        alice = devTokens.sign("alice", "Alice", List.of("SUPPORT_AGENT"));
        carol = devTokens.sign("carol", "Carol", List.of("SUPPORT_AGENT"));
        documents.add(acme, "Handbook", "# Returns\nReturn any item within thirty days for a refund.", "root");
        embedder.run();
    }

    @AfterEach
    void reset() {
        AI.failWith = 0;
    }

    UUID chatAsking(String question) {
        UUID id = service.startOrResume(acme, UUID.randomUUID()).conversation().getId();
        service.addContact(id, "Pat", "pat-" + id + "@example.test");
        service.handoff(id, HandoffReason.CUSTOMER_REQUEST, SenderType.CUSTOMER);
        service.addCustomerMessage(id, "c-" + UUID.randomUUID(), question, null);
        return id;
    }

    MvcTestResult suggest(String token, UUID id) {
        return mvc.post().uri("/api/v1/agent/conversations/" + id + "/suggestion")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    @Test
    void draftsAReplyFromTheDocuments() {
        MvcTestResult result = suggest(alice, chatAsking("Can I return a refund item after two weeks?"));

        assertThat(result).hasStatusOk().bodyJson().extractingPath("$.text")
            .isEqualTo("You can return any item within thirty days.");
        assertThat(result).bodyJson().extractingPath("$.sources[0].heading").isEqualTo("Returns");
        assertThat(result).bodyJson().extractingPath("$.reason").isNull();
        // The model got the passage and the question, and the company's name in the instruction.
        assertThat(AI.lastChat).contains("Return any item within thirty days").contains("after two weeks").contains("Acme");
    }

    @Test
    void doesntAskTheAiWhenNothingMatches() {
        int before = AI.chats.get();
        assertThat(suggest(alice, chatAsking("Write me a poem about the moon"))).bodyJson()
            .extractingPath("$.reason").isEqualTo("NO_MATCH");
        assertThat(AI.chats.get()).isEqualTo(before);
    }

    @Test
    void nothingWhenTheModelHasNoAnswerOrBreaksTheRules() {
        UUID id = chatAsking("Can I return a refund item?");
        AI.chatReply = "{\"answer\": \"NONE\", \"sources\": []}";
        assertThat(suggest(alice, id)).bodyJson().extractingPath("$.reason").isEqualTo("NO_ANSWER");
        AI.chatReply = "{\"answer\": \"Sure, see www.elsewhere.test\", \"sources\": [1]}";
        assertThat(suggest(alice, id)).bodyJson().extractingPath("$.reason").isEqualTo("NO_ANSWER");
        AI.chatReply = "{\"answer\": \"Yes.\", \"sources\": [7]}";
        assertThat(suggest(alice, id)).bodyJson().extractingPath("$.reason").isEqualTo("NO_ANSWER");
    }

    @Test
    void aFailingProviderJustMeansNoSuggestion() {
        UUID id = chatAsking("Can I return a refund item?");
        AI.failWith = 500;
        assertThat(suggest(alice, id)).hasStatusOk().bodyJson().extractingPath("$.reason").isEqualTo("AI_UNAVAILABLE");
    }

    @Test
    void offUnlessTheCompanyTurnedItOn() {
        jdbc.update("update tenants set ai_enabled = false where id = ?", acme);
        int before = AI.chats.get() + AI.requests.get();
        assertThat(suggest(alice, chatAsking("Can I return a refund item?"))).bodyJson()
            .extractingPath("$.reason").isEqualTo("OFF");
        assertThat(AI.chats.get() + AI.requests.get()).isEqualTo(before);
        assertThat(mvc.get().uri("/api/v1/agent/tenants").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
            .bodyJson().extractingPath("$.items[0].ai").isEqualTo(false);
    }

    @Test
    void adminsSeeWhetherTheServerHasAiAndTurnItOnPerCompany() throws Exception {
        String root = devTokens.sign("root", "Root", List.of("QUOKRA_ADMIN"));
        assertThat(mvc.get().uri("/api/v1/admin/ai").header(HttpHeaders.AUTHORIZATION, "Bearer " + root))
            .bodyJson().extractingPath("$.configured").isEqualTo(true);

        var tenant = mvc.get().uri("/api/v1/admin/tenants/acme").header(HttpHeaders.AUTHORIZATION, "Bearer " + root).exchange();
        assertThat(tenant).bodyJson().extractingPath("$.aiEnabled").isEqualTo(true);
        String body = tenant.getResponse().getContentAsString().replace("\"aiEnabled\":true", "\"aiEnabled\":false");
        assertThat(mvc.put().uri("/api/v1/admin/tenants/acme").header(HttpHeaders.AUTHORIZATION, "Bearer " + root)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body)).hasStatusOk()
            .bodyJson().extractingPath("$.aiEnabled").isEqualTo(false);
        assertThat(suggest(alice, chatAsking("Can I return a refund item?"))).bodyJson()
            .extractingPath("$.reason").isEqualTo("OFF");
    }

    @Test
    void onlyAgentsOfThatCompany() {
        assertThat(suggest(carol, chatAsking("Can I return a refund item?"))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/api/v1/agent/tenants").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
            .bodyJson().extractingPath("$.items[0].ai").isEqualTo(true);
    }
}
