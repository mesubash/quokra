package io.github.mesubash.quokra.ai;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mesubash.quokra.TestcontainersConfiguration;
import io.github.mesubash.quokra.conversation.AiQuestion;
import io.github.mesubash.quokra.conversation.ConversationService;
import io.github.mesubash.quokra.conversation.Enums.SenderType;
import io.github.mesubash.quokra.conversation.Message;
import io.github.mesubash.quokra.conversation.Status;
import io.github.mesubash.quokra.knowledge.KnowledgeDocuments;
import io.github.mesubash.quokra.knowledge.KnowledgeEmbedder;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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

// The bot asking the AI when it can't match a typed question (v3 step 3b), against a fake AI. The test
// transaction never commits, so the listener doesn't run by itself; each test hands the question over.
@SpringBootTest(properties = {"quokra.ai.key=test-key", "quokra.ai.min-score=0.3"})
@Import(TestcontainersConfiguration.class)
@Transactional
@RecordApplicationEvents
class AiAnswersTests {

    static final FakeAi AI = new FakeAi();

    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry registry) {
        registry.add("quokra.ai.base-url", AI::baseUrl);
    }

    @Autowired
    ApplicationEvents events;

    @Autowired
    ConversationService service;

    @Autowired
    CustomerAnswers answers;

    @Autowired
    KnowledgeDocuments documents;

    @Autowired
    KnowledgeEmbedder embedder;

    @Autowired
    JdbcTemplate jdbc;

    UUID acme = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AI.failWith = 0;
        AI.chatReply = "{\"answer\": \"You can return any item within thirty days.\", \"sources\": [1]}";
        jdbc.update("""
            insert into tenants (id, slug, display_name, publishable_key, bot_config, ai_answers_enabled)
            values (?, 'acme', 'Acme', 'pk_test_acme', 'test', true)
            """, acme);
        documents.add(acme, "Handbook", "# Returns\nReturn any item within thirty days for a refund.", "root");
        embedder.run();
    }

    @AfterEach
    void reset() {
        AI.failWith = 0;
    }

    UUID chat() {
        return service.startOrResume(acme, UUID.randomUUID()).conversation().getId();
    }

    // The customer types, and the AI's turn runs as it would after commit.
    void ask(UUID id, String question) {
        int before = questions().size();
        service.addCustomerMessage(id, "c-" + UUID.randomUUID(), question, null);
        if (questions().size() > before) {
            answers.answer(questions().getLast());
        }
    }

    List<AiQuestion> questions() {
        return events.stream(AiQuestion.class).toList();
    }

    Message last(UUID id) {
        return service.messages(id, null, 1).getLast();
    }

    @Test
    void answersFromTheDocumentsWithSourcesAndTwoButtons() {
        UUID id = chat();
        ask(id, "Can I return a refund item after two weeks?");

        Message answer = last(id);
        assertThat(answer.getSenderType()).isEqualTo(SenderType.BOT);
        assertThat(answer.getBody()).isEqualTo("You can return any item within thirty days.");
        assertThat(answer.getSources()).containsExactly(new Message.Source("Handbook", "Returns"));
        assertThat(answer.getOptions()).extracting(o -> o.id()).containsExactly("ai-helped", "human");
        assertThat(service.get(id).getFallbackCount()).isZero();
    }

    @Test
    void thatHelpsGetsTheMenuAgain() {
        UUID id = chat();
        ask(id, "Can I return a refund item?");
        service.addCustomerMessage(id, "c-helped", null, ConversationService.AI_HELPED_OPTION);

        assertThat(service.messages(id, null, 2).getFirst().getBody()).isEqualTo("That helps");
        assertThat(last(id).getBody()).isEqualTo("Glad that helped. Anything else?");
        assertThat(last(id).getOptions()).extracting(o -> o.id()).contains("pricing", "human");
    }

    @Test
    void noAnswerIsAnOrdinaryMissThenAHandoff() {
        AI.chatReply = "{\"answer\": \"NONE\", \"sources\": []}";
        UUID id = chat();
        ask(id, "Can I return a refund item?");
        assertThat(last(id).getBody()).isEqualTo("Fallback message.");
        assertThat(last(id).getSources()).isNull();

        // Second miss: handoff, which for a guest starts with asking for their email.
        ask(id, "And a refund for shoes?");
        assertThat(service.get(id).getPendingHandoff()).isNotNull();
        assertThat(service.get(id).getStatus()).isEqualTo(Status.BOT);
    }

    @Test
    void offTopicNeverReachesTheModel() {
        int chats = AI.chats.get();
        UUID id = chat();
        ask(id, "Write me a poem about the moon");
        assertThat(last(id).getBody()).isEqualTo("Fallback message.");
        assertThat(AI.chats.get()).isEqualTo(chats);
    }

    @Test
    void aFailingProviderIsAnOrdinaryMiss() {
        AI.failWith = 503;
        UUID id = chat();
        ask(id, "Can I return a refund item?");
        assertThat(last(id).getBody()).isEqualTo("Fallback message.");
    }

    @Test
    void menuAndKeywordsStillAnswerFirst() {
        UUID id = chat();
        ask(id, "What does it cost?");
        assertThat(last(id).getBody()).isEqualTo("Pricing answer.");
        assertThat(questions()).isEmpty();
    }

    @Test
    void offUnlessTheCompanyTurnedItOn() {
        jdbc.update("update tenants set ai_answers_enabled = false where id = ?", acme);
        UUID id = chat();
        ask(id, "Can I return a refund item?");
        assertThat(questions()).isEmpty();
        assertThat(last(id).getBody()).isEqualTo("Fallback message.");
    }

    @Test
    void aLateAnswerToAnOlderQuestionIsDropped() {
        UUID id = chat();
        service.addCustomerMessage(id, "c-1", "Can I return a refund item?", null);
        AiQuestion first = questions().getLast();
        service.addCustomerMessage(id, "c-2", "Also, what does it cost?", null);   // the bot answers this one

        answers.answer(first);
        assertThat(last(id).getBody()).isEqualTo("Pricing answer.");
    }

    @Test
    void atMostSoManyAiAnswersPerChat() {
        UUID id = chat();
        for (int i = 0; i < 10; i++) {
            ask(id, "Can I return a refund item, take " + i + "?");
        }
        int asked = questions().size();
        service.addCustomerMessage(id, "c-more", "Can I return a refund item once more?", null);
        assertThat(questions()).hasSize(asked);
        assertThat(last(id).getBody()).isEqualTo("Fallback message.");
    }
}
