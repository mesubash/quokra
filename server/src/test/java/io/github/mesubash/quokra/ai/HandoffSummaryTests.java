package io.github.mesubash.quokra.ai;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mesubash.quokra.TestcontainersConfiguration;
import io.github.mesubash.quokra.conversation.ConversationNote;
import io.github.mesubash.quokra.conversation.ConversationService;
import io.github.mesubash.quokra.conversation.Enums.HandoffReason;
import io.github.mesubash.quokra.conversation.Enums.SenderType;
import io.github.mesubash.quokra.conversation.HandedOff;
import io.github.mesubash.quokra.conversation.Status;
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

// The AI's summary for the agent when a chat joins the queue (v3 step 6), against a fake AI.
@SpringBootTest(properties = "quokra.ai.key=test-key")
@Import(TestcontainersConfiguration.class)
@Transactional
@RecordApplicationEvents
class HandoffSummaryTests {

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
    HandoffSummaries summaries;

    @Autowired
    AiUsage usage;

    @Autowired
    JdbcTemplate jdbc;

    UUID acme = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AI.failWith = 0;
        AI.chatReply = "{\"summary\": \"Pat wants a refund for order 1234, which arrived damaged.\"}";
        jdbc.update("""
            insert into tenants (id, slug, display_name, publishable_key, bot_config, ai_enabled)
            values (?, 'acme', 'Acme', 'pk_test_acme', 'test', true)
            """, acme);
    }

    @AfterEach
    void reset() {
        AI.failWith = 0;
    }

    // A chat where the customer typed something, then asked for a person; the summary runs as after commit.
    List<ConversationNote> handOff(String... typed) {
        UUID id = service.startOrResume(acme, UUID.randomUUID()).conversation().getId();
        service.addContact(id, "Pat", "pat-" + id + "@example.test");
        for (String text : typed) {
            service.addCustomerMessage(id, "c-" + UUID.randomUUID(), text, null);
        }
        // Two misses already hand the chat over by themselves.
        if (service.get(id).getStatus() == Status.BOT) {
            service.handoff(id, HandoffReason.CUSTOMER_REQUEST, SenderType.CUSTOMER);
        }
        events.stream(HandedOff.class).filter(e -> e.conversationId().equals(id)).forEach(summaries::summarize);
        return service.notes(id);
    }

    @Test
    void theAgentGetsASummaryAsANote() {
        List<ConversationNote> notes = handOff("My order 1234 arrived damaged", "I'd like my money back");

        assertThat(notes).singleElement().satisfies(n -> {
            assertThat(n.getAuthorName()).isEqualTo("AI summary");
            assertThat(n.getBody()).isEqualTo("Pat wants a refund for order 1234, which arrived damaged.");
        });
        assertThat(AI.lastChat).contains("Customer: My order 1234 arrived damaged").contains("Acme");
        assertThat(usage.today(acme).calls()).isEqualTo(1);
    }

    @Test
    void nothingToSummariseWithoutTypedMessages() {
        int chats = AI.chats.get();
        assertThat(handOff()).isEmpty();
        assertThat(AI.chats.get()).isEqualTo(chats);
    }

    @Test
    void offUnlessTheCompanyUsesAiForAgents() {
        jdbc.update("update tenants set ai_enabled = false where id = ?", acme);
        int chats = AI.chats.get();
        assertThat(handOff("My order 1234 arrived damaged")).isEmpty();
        assertThat(AI.chats.get()).isEqualTo(chats);
    }

    @Test
    void aFailingProviderOrABadSummaryLeavesNoNote() {
        AI.failWith = 503;
        assertThat(handOff("My order 1234 arrived damaged")).isEmpty();

        AI.failWith = 0;
        AI.chatReply = "{\"summary\": \"<script>alert(1)</script>\"}";
        assertThat(handOff("My order 1234 arrived damaged")).isEmpty();
    }
}
