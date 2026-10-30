package io.github.mesubash.quokra.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mesubash.quokra.TestcontainersConfiguration;
import io.github.mesubash.quokra.bot.BotConfig;
import io.github.mesubash.quokra.common.ApiException;
import io.github.mesubash.quokra.conversation.Enums.EventType;
import io.github.mesubash.quokra.conversation.Enums.HandoffReason;
import io.github.mesubash.quokra.conversation.Enums.Priority;
import io.github.mesubash.quokra.conversation.Enums.SenderType;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// Uses src/test/resources/bots/test.yml.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ConversationServiceTests {

    static final UUID TENANT = UUID.randomUUID();
    static final UUID NO_BOT_TENANT = UUID.randomUUID();

    @Autowired
    ConversationService service;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    UUID visitor = UUID.randomUUID();

    @BeforeEach
    void addTenants() {
        jdbc.update("""
            insert into tenants (id, slug, display_name, publishable_key, bot_config)
            values (?, 'acme', 'Acme', 'pk_test_acme', 'test'),
                   (?, 'nobot', 'No Bot', 'pk_test_nobot', 'missing')
            """, TENANT, NO_BOT_TENANT);
    }

    // A guest who already left their details, so handoffs go straight to the queue.
    UUID start() {
        UUID id = startAsGuest();
        service.addContact(id, "Pat", "pat@example.test");
        return id;
    }

    UUID startAsGuest() {
        return service.startOrResume(TENANT, visitor).conversation().getId();
    }

    List<Message> history(UUID id) {
        return service.messages(id, null, 50);
    }

    @Test
    void startCreatesConversationWithGreetingAndMenu() {
        var started = service.startOrResume(TENANT, visitor);

        assertThat(started.created()).isTrue();
        assertThat(started.conversation().getStatus()).isEqualTo(Status.BOT);
        assertThat(history(started.conversation().getId())).singleElement().satisfies(m -> {
            assertThat(m.getSenderType()).isEqualTo(SenderType.BOT);
            assertThat(m.getBody()).isEqualTo("Hello from the test bot.");
            assertThat(m.getOptions()).extracting(BotConfig.Option::id).containsExactly("pricing", "account", "human");
        });
        assertThat(service.events(started.conversation().getId()))
            .extracting(ConversationEvent::getType).containsExactly(EventType.CREATED);
    }

    @Test
    void startAgainResumesTheSameConversation() {
        UUID first = start();
        var again = service.startOrResume(TENANT, visitor);

        assertThat(again.created()).isFalse();
        assertThat(again.conversation().getId()).isEqualTo(first);
    }

    @Test
    void botAnswersFromTheMenu() {
        UUID id = start();

        service.addCustomerMessage(id, "c-1", "What does it cost?", null);

        assertThat(history(id)).extracting(Message::getSenderType, Message::getBody).containsExactly(
            tuple(SenderType.BOT, "Hello from the test bot."),
            tuple(SenderType.CUSTOMER, "What does it cost?"),
            tuple(SenderType.BOT, "Pricing answer."));
        assertThat(history(id).getLast().getOptions()).isNotEmpty();
    }

    @Test
    void menuButtonIsStoredWithItsTitle() {
        UUID id = start();

        Message sent = service.addCustomerMessage(id, "c-1", null, "account");

        assertThat(sent.getBody()).isEqualTo("Account help");
        assertThat(history(id).getLast().getBody()).isEqualTo("Account answer.");
    }

    @Test
    void unknownMenuButtonIsRejected() {
        UUID id = start();

        assertThatThrownBy(() -> service.addCustomerMessage(id, "c-1", null, "nope"))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_ERROR"));
    }

    @Test
    void sameClientMessageIdIsStoredOnce() {
        UUID id = start();

        Message first = service.addCustomerMessage(id, "c-1", "price", null);
        Message retry = service.addCustomerMessage(id, "c-1", "price", null);

        assertThat(retry.getId()).isEqualTo(first.getId());
        assertThat(history(id)).hasSize(3);
    }

    @Test
    void messagesAfterReturnsOnlyNewerOnes() {
        UUID id = start();
        Message one = service.addCustomerMessage(id, "c-1", "price", null);
        service.addCustomerMessage(id, "c-2", "password", null);

        assertThat(service.messages(id, one.getId(), 50)).extracting(Message::getBody)
            .containsExactly("Pricing answer.", "password", "Account answer.");
    }

    @Test
    void oneMissGetsTheFallback() {
        UUID id = start();

        service.addCustomerMessage(id, "c-1", "hello", null);

        assertThat(history(id).getLast().getBody()).isEqualTo("Fallback message.");
        assertThat(service.get(id).getStatus()).isEqualTo(Status.BOT);
    }

    @Test
    void tooManyMissesHandOff() {
        UUID id = start();

        service.addCustomerMessage(id, "c-1", "hello", null);
        service.addCustomerMessage(id, "c-2", "still nothing", null);

        Conversation c = service.get(id);
        assertThat(c.getStatus()).isEqualTo(Status.WAITING);
        assertThat(c.getHandoffReason()).isEqualTo(HandoffReason.FALLBACK_LIMIT);
        assertThat(history(id).getLast()).satisfies(m -> {
            assertThat(m.getSenderType()).isEqualTo(SenderType.SYSTEM);
            assertThat(m.getBody()).isEqualTo("Handoff message.");
        });
    }

    @Test
    void anAnswerResetsTheMisses() {
        UUID id = start();

        service.addCustomerMessage(id, "c-1", "hello", null);
        service.addCustomerMessage(id, "c-2", "price", null);
        service.addCustomerMessage(id, "c-3", "hello again", null);

        assertThat(service.get(id).getStatus()).isEqualTo(Status.BOT);
    }

    @Test
    void urgentKeywordHandsOffWithTopPriority() {
        UUID id = start();

        service.addCustomerMessage(id, "c-1", "My payment failed, the price was wrong", null);

        Conversation c = service.get(id);
        assertThat(c.getStatus()).isEqualTo(Status.WAITING);
        assertThat(c.getHandoffReason()).isEqualTo(HandoffReason.URGENT);
        assertThat(c.getPriority()).isEqualTo(Priority.URGENT);
        assertThat(history(id).getLast().getBody()).isEqualTo("Urgent message.");
    }

    @Test
    void askingForAPersonHandsOff() {
        UUID id = start();

        service.addCustomerMessage(id, "c-1", null, BotConfig.HUMAN_OPTION);

        assertThat(service.get(id).getHandoffReason()).isEqualTo(HandoffReason.CUSTOMER_REQUEST);
    }

    @Test
    void botIsSilentOnceHandedOff() {
        UUID id = start();
        service.handoff(id, HandoffReason.CUSTOMER_REQUEST, SenderType.CUSTOMER);

        service.addCustomerMessage(id, "c-1", "price", null);

        assertThat(history(id).getLast().getSenderType()).isEqualTo(SenderType.CUSTOMER);
    }

    @Test
    void missingBotConfigHandsOffInsteadOfFailing() {
        UUID id = service.startOrResume(NO_BOT_TENANT, visitor).conversation().getId();
        service.addContact(id, "Pat", "pat@example.test");

        service.addCustomerMessage(id, "c-1", "hello", null);

        Conversation c = service.get(id);
        assertThat(c.getStatus()).isEqualTo(Status.WAITING);
        assertThat(c.getHandoffReason()).isEqualTo(HandoffReason.BOT_ERROR);
        assertThat(history(id).getFirst().getBody()).isEqualTo(ConversationService.GREETING);
    }

    @Test
    void emptyAndTooLongMessagesAreRejected() {
        UUID id = start();

        assertThatThrownBy(() -> service.addCustomerMessage(id, "c-1", "  ", null))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_ERROR"));
        assertThatThrownBy(() -> service.addCustomerMessage(id, "c-2", "x".repeat(Message.MAX_BODY_LENGTH + 1), null))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("MESSAGE_TOO_LONG"));
    }

    @Test
    void handoffMovesToWaitingWithReason() {
        UUID id = start();

        Conversation c = service.handoff(id, HandoffReason.CUSTOMER_REQUEST, SenderType.CUSTOMER);

        assertThat(c.getStatus()).isEqualTo(Status.WAITING);
        assertThat(c.getPriority()).isEqualTo(Priority.NORMAL);
        assertThat(history(id).getLast().getBody()).isEqualTo("Handoff message.");
        assertThat(service.events(id)).extracting(ConversationEvent::getType)
            .containsExactly(EventType.CREATED, EventType.CONTACT_ADDED, EventType.HANDOFF);
    }

    @Test
    void outsideOpeningHoursTheCustomerHearsWhenTheTeamIsBack() {
        // Open only on a day three days from now, so it's closed today.
        String day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(3).getDayOfWeek().name().substring(0, 3);
        jdbc.update("update tenants set opening_hours = ? where id = ?",
            "{\"timezone\":\"UTC\",\"days\":{\"" + day + "\":\"09:00-10:00\"}}", TENANT);
        UUID id = start();

        Conversation c = service.handoff(id, HandoffReason.CUSTOMER_REQUEST, SenderType.CUSTOMER);

        assertThat(c.getStatus()).isEqualTo(Status.WAITING);
        assertThat(history(id).getLast().getBody())
            .startsWith("We're away right now and back ")
            .contains("at 09:00 (UTC)");
    }

    @Test
    void secondHandoffIsRejected() {
        UUID id = start();
        service.handoff(id, HandoffReason.CUSTOMER_REQUEST, SenderType.CUSTOMER);

        assertThatThrownBy(() -> service.handoff(id, HandoffReason.CUSTOMER_REQUEST, SenderType.CUSTOMER))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INVALID_TRANSITION"));
    }

    @Test
    void messageOnResolvedConversationReopensIt() {
        UUID id = start();
        service.handoff(id, HandoffReason.URGENT, SenderType.BOT);
        Conversation c = service.get(id);
        c.moveTo(StateMachine.next(c.getStatus(), Transition.RESOLVE), c.getUpdatedAt());

        service.addCustomerMessage(id, "c-9", "price", null);

        Conversation reopened = service.get(id);
        assertThat(reopened.getStatus()).isEqualTo(Status.BOT);
        assertThat(reopened.getPriority()).isEqualTo(Priority.NORMAL);
        assertThat(reopened.getHandoffReason()).isNull();
        assertThat(history(id).getLast().getBody()).isEqualTo("Pricing answer.");
        assertThat(service.events(id)).extracting(ConversationEvent::getType).endsWith(EventType.REOPENED);
    }

    @Test
    void unknownConversationIsNotFound() {
        assertThatThrownBy(() -> service.get(UUID.randomUUID()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CONVERSATION_NOT_FOUND"));
    }

    @Test
    void databaseAllowsOnlyOneOpenConversationPerVisitor() {
        start();

        assertThatThrownBy(() -> conversations.saveAndFlush(Conversation.start(TENANT, visitor, Instant.now())))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test
    void guestLeavesTheirDetailsBeforeJoiningTheQueue() {
        UUID id = startAsGuest();

        service.addCustomerMessage(id, "c-1", null, BotConfig.HUMAN_OPTION);

        Conversation c = service.get(id);
        assertThat(c.getStatus()).isEqualTo(Status.BOT);
        assertThat(c.getPendingHandoff()).isEqualTo(HandoffReason.CUSTOMER_REQUEST);
        assertThat(history(id).getLast().getBody()).isEqualTo(ConversationService.ASK_EMAIL);

        service.addContact(id, " Pat Doe ", "Pat@Example.test");

        c = service.get(id);
        assertThat(c.getStatus()).isEqualTo(Status.WAITING);
        assertThat(c.getHandoffReason()).isEqualTo(HandoffReason.CUSTOMER_REQUEST);
        assertThat(c.getPendingHandoff()).isNull();
        assertThat(c.getCustomerName()).isEqualTo("Pat Doe");
        assertThat(c.getCustomerEmail()).isEqualTo("Pat@Example.test");
    }

    @Test
    void botAsksAgainForAnEmailInsteadOfAnsweringQuestions() {
        UUID id = startAsGuest();
        service.handoff(id, HandoffReason.CUSTOMER_REQUEST, SenderType.CUSTOMER);

        service.addCustomerMessage(id, "c-1", "price", null);
        service.handoff(id, HandoffReason.CUSTOMER_REQUEST, SenderType.CUSTOMER);

        assertThat(history(id).getLast().getBody()).isEqualTo(ConversationService.BAD_EMAIL);
        assertThat(service.get(id).getStatus()).isEqualTo(Status.BOT);
    }

    @Test
    void guestGivesEmailThenNameInTheChat() {
        UUID id = startAsGuest();
        service.handoff(id, HandoffReason.CUSTOMER_REQUEST, SenderType.CUSTOMER);

        service.addCustomerMessage(id, "c-1", "pat@example.test", null);
        assertThat(history(id).getLast().getBody()).isEqualTo(ConversationService.ASK_NAME);
        assertThat(history(id).getLast().getOptions()).extracting(BotConfig.Option::id).containsExactly("skip");

        service.addCustomerMessage(id, "c-2", "Pat", null);

        Conversation c = service.get(id);
        assertThat(c.getStatus()).isEqualTo(Status.WAITING);
        assertThat(c.getCustomerName()).isEqualTo("Pat");
        assertThat(c.getCustomerEmail()).isEqualTo("pat@example.test");
        assertThat(c.getContactId()).isNotNull();
    }

    @Test
    void sameEmailMeansTheSameContact() {
        UUID first = startAsGuest();
        service.addContact(first, "Pat", "pat@example.test");
        visitor = UUID.randomUUID();
        UUID second = startAsGuest();

        service.addContact(second, "Patricia", "PAT@example.test");

        assertThat(service.get(second).getContactId()).isEqualTo(service.get(first).getContactId());
        assertThat(service.get(second).getCustomerName()).isEqualTo("Patricia");
    }

    @Test
    void startingANewChatClosesTheOldOneAndKeepsTheContact() {
        UUID old = start();
        service.handoff(old, HandoffReason.CUSTOMER_REQUEST, SenderType.CUSTOMER);
        service.accept(old, new AgentRef("sam", "Sam"));

        Conversation fresh = service.startNew(TENANT, visitor, null);

        assertThat(fresh.getId()).isNotEqualTo(old);
        assertThat(fresh.getStatus()).isEqualTo(Status.BOT);
        assertThat(fresh.getCustomerEmail()).isEqualTo("pat@example.test");
        assertThat(history(fresh.getId())).singleElement().extracting(Message::getBody).isEqualTo("Hello from the test bot.");
        assertThat(service.get(old).getStatus()).isEqualTo(Status.RESOLVED);
        assertThat(service.events(old)).last().satisfies(e -> {
            assertThat(e.getType()).isEqualTo(EventType.RESOLVED);
            assertThat(e.getReason()).isEqualTo(ConversationService.CUSTOMER_ENDED);
        });
        assertThat(service.current(TENANT, visitor)).get().extracting(Conversation::getId).isEqualTo(fresh.getId());
    }

    @Test
    void blockedContactCantStartOver() {
        start();
        entityManager.flush();
        jdbc.update("update contacts set blocked_at = now()");
        entityManager.clear();

        assertThatThrownBy(() -> service.startNew(TENANT, visitor, null))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CONTACT_BLOCKED"));
    }

    @Test
    void replyQuotesAnEarlierMessageOfTheSameChat() {
        UUID id = start();
        service.handoff(id, HandoffReason.CUSTOMER_REQUEST, SenderType.CUSTOMER);
        service.accept(id, new AgentRef("sam", "Sam"));
        Message question = service.addCustomerMessage(id, "c-1", "Where is my order?\nIt's late.", null);

        Message reply = service.addAgentMessage(id, new AgentRef("sam", "Sam"), "a-1", "On its way", question.getId());

        assertThat(MessageView.of(reply).replyTo())
            .isEqualTo(new Message.Quote(question.getId(), SenderType.CUSTOMER, "Pat", "Where is my order?"));
    }

    @Test
    void cantReopenAnOldChatWhileTheCustomerHasANewerOne() {
        UUID old = start();
        service.handoff(old, HandoffReason.CUSTOMER_REQUEST, SenderType.CUSTOMER);
        AgentRef sam = new AgentRef("sam", "Sam");
        service.accept(old, sam);
        service.resolve(old, sam);
        service.startNew(TENANT, visitor, null);

        assertThatThrownBy(() -> service.reopen(old, sam))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("NEWER_CHAT_OPEN"));
    }

    @Test
    void cantReplyToTheBotOrAnotherChat() {
        UUID id = start();
        Message greeting = history(id).getFirst();
        assertThatThrownBy(() -> service.addCustomerMessage(id, "c-1", "hi", null, greeting.getId()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_ERROR"));

        visitor = UUID.randomUUID();
        UUID other = start();
        Message elsewhere = service.addCustomerMessage(other, "c-2", "mine", null);
        assertThatThrownBy(() -> service.addCustomerMessage(id, "c-3", "hi", null, elsewhere.getId()))
            .isInstanceOf(ApiException.class);
    }

    @Test
    void blockedContactCantChat() {
        UUID id = start();
        entityManager.flush();
        jdbc.update("update contacts set blocked_at = now()");
        entityManager.clear();

        assertThatThrownBy(() -> service.addCustomerMessage(id, "c-1", "hello", null))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CONTACT_BLOCKED"));
        visitor = UUID.randomUUID();
        UUID fresh = startAsGuest();
        assertThatThrownBy(() -> service.addContact(fresh, "Pat", "pat@example.test"))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CONTACT_BLOCKED"));
    }
}
