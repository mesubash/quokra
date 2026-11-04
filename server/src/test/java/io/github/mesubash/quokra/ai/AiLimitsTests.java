package io.github.mesubash.quokra.ai;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mesubash.quokra.TestcontainersConfiguration;
import io.github.mesubash.quokra.ai.AiUsage.Limit;
import io.github.mesubash.quokra.conversation.AiQuestion;
import io.github.mesubash.quokra.conversation.ConversationService;
import io.github.mesubash.quokra.knowledge.KnowledgeDocuments;
import io.github.mesubash.quokra.knowledge.KnowledgeEmbedder;
import io.github.mesubash.quokra.tenant.TenantRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

// The cost limits (v3 step 5), set low here: reaching one means no AI that time, never an error.
@SpringBootTest(properties = {"quokra.ai.key=test-key", "quokra.ai.retry-waits=PT0.01S,PT0.01S", "quokra.ai.min-score=0.3", "quokra.ai.per-ip-hour=2",
    "quokra.ai.per-company-day=3", "quokra.ai.per-server-day=5"})
@Import(TestcontainersConfiguration.class)
@Transactional
@RecordApplicationEvents
class AiLimitsTests {

    static final FakeAi AI = new FakeAi();

    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry registry) {
        registry.add("quokra.ai.base-url", AI::baseUrl);
    }

    @Autowired
    AiUsage usage;

    @Autowired
    ApplicationEvents events;

    @Autowired
    ConversationService service;

    @Autowired
    CustomerAnswers answers;

    @Autowired
    ReplySuggester suggester;

    @Autowired
    TenantRepository tenants;

    @Autowired
    KnowledgeDocuments documents;

    @Autowired
    KnowledgeEmbedder embedder;

    @Autowired
    JdbcTemplate jdbc;

    UUID acme = UUID.randomUUID();
    UUID globex = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AI.failWith = 0;
        AI.chatReply = "{\"answer\": \"You can return any item within thirty days.\", \"sources\": [1]}";
        jdbc.update("""
            insert into tenants (id, slug, display_name, publishable_key, bot_config, ai_enabled, ai_answers_enabled)
            values (?, 'acme', 'Acme', 'pk_test_acme', 'test', true, true),
                   (?, 'globex', 'Globex', 'pk_test_globex', 'test', true, true)
            """, acme, globex);
        documents.add(acme, "Handbook", "# Returns\nReturn any item within thirty days for a refund.", "root");
        embedder.run();
    }

    // A random address, so the in-memory per-IP counts of other tests don't matter.
    static String ip() {
        return "10.0." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
    }

    @Test
    void aCompanyStopsAtItsDailyLimit() {
        for (int i = 0; i < 3; i++) {
            assertThat(usage.use(acme, null)).isNull();
        }
        assertThat(usage.use(acme, null)).isEqualTo(Limit.PER_COMPANY);
        assertThat(usage.today(acme)).isEqualTo(new AiUsage.Today(3, 3));
        assertThat(usage.use(globex, null)).isNull();
    }

    @Test
    void theServerStopsAtItsDailyTotal() {
        for (int i = 0; i < 3; i++) {
            usage.use(acme, null);
        }
        usage.use(globex, null);
        usage.use(globex, null);
        assertThat(usage.use(globex, null)).isEqualTo(Limit.PER_SERVER);
    }

    @Test
    void anIpStopsAtItsHourlyLimitAgentsDont() {
        String ip = ip();
        assertThat(usage.use(acme, ip)).isNull();
        assertThat(usage.use(acme, ip)).isNull();
        assertThat(usage.use(acme, ip)).isEqualTo(Limit.PER_IP);
        assertThat(usage.use(acme, null)).isNull();
    }

    @Test
    void overTheLimitTheCustomerGetsTheUsualFallback() {
        for (int i = 0; i < 3; i++) {
            usage.use(acme, null);
        }
        int chats = AI.chats.get();
        UUID id = service.startOrResume(acme, UUID.randomUUID()).conversation().getId();
        service.addCustomerMessage(id, "c-1", "Can I return a refund item?", null);
        answers.answer(events.stream(AiQuestion.class).toList().getLast(), ip());

        assertThat(service.messages(id, null, 1).getLast().getBody()).isEqualTo("Fallback message.");
        assertThat(AI.chats.get()).isEqualTo(chats);
    }

    @Test
    void overTheLimitAgentsGetNoSuggestionAndAReason() {
        for (int i = 0; i < 3; i++) {
            usage.use(acme, null);
        }
        UUID id = service.startOrResume(acme, UUID.randomUUID()).conversation().getId();
        service.addCustomerMessage(id, "c-1", "Can I return a refund item?", null);

        var suggestion = suggester.suggest(tenants.findById(acme).orElseThrow(), service.messages(id, null, 20));
        assertThat(suggestion.reason()).isEqualTo(ReplySuggester.Reason.LIMIT);
    }

    @Test
    void onlyRealCallsCount() {
        UUID id = service.startOrResume(acme, UUID.randomUUID()).conversation().getId();
        service.addCustomerMessage(id, "c-1", "Write me a poem about the moon", null);
        events.stream(AiQuestion.class).forEach(q -> answers.answer(q, ip()));
        assertThat(usage.today(acme).calls()).isZero();

        service.addCustomerMessage(id, "c-2", "Can I return a refund item?", null);
        answers.answer(events.stream(AiQuestion.class).toList().getLast(), ip());
        assertThat(usage.today(acme).calls()).isEqualTo(1);
    }
}
