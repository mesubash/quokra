package io.github.mesubash.quokra.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mesubash.quokra.TestcontainersConfiguration;
import io.github.mesubash.quokra.common.ApiException;
import io.github.mesubash.quokra.conversation.Enums.EventType;
import io.github.mesubash.quokra.conversation.Enums.HandoffReason;
import io.github.mesubash.quokra.conversation.Enums.Priority;
import io.github.mesubash.quokra.conversation.Enums.SenderType;
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

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ConversationServiceTests {

    static final UUID TENANT = UUID.randomUUID();

    @Autowired
    ConversationService service;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    JdbcTemplate jdbc;

    UUID visitor = UUID.randomUUID();

    @BeforeEach
    void addTenant() {
        jdbc.update("""
            insert into tenants (id, slug, display_name, publishable_key, bot_config)
            values (?, 'acme', 'Acme', 'pk_test_acme', 'acme')
            """, TENANT);
    }

    @Test
    void startCreatesConversationWithGreeting() {
        var started = service.startOrResume(TENANT, visitor);

        assertThat(started.created()).isTrue();
        assertThat(started.conversation().getStatus()).isEqualTo(Status.BOT);
        assertThat(service.messages(started.conversation().getId(), null, 50))
            .singleElement()
            .satisfies(m -> {
                assertThat(m.getSenderType()).isEqualTo(SenderType.BOT);
                assertThat(m.getBody()).isEqualTo(ConversationService.GREETING);
            });
        assertThat(service.events(started.conversation().getId()))
            .extracting(ConversationEvent::getType).containsExactly(EventType.CREATED);
    }

    @Test
    void startAgainResumesTheSameConversation() {
        UUID first = service.startOrResume(TENANT, visitor).conversation().getId();
        var again = service.startOrResume(TENANT, visitor);

        assertThat(again.created()).isFalse();
        assertThat(again.conversation().getId()).isEqualTo(first);
    }

    @Test
    void customerMessageIsStored() {
        UUID id = service.startOrResume(TENANT, visitor).conversation().getId();

        service.addCustomerMessage(id, "c-1", "How do I reset my password?");

        assertThat(service.messages(id, null, 50))
            .extracting(Message::getSenderType, Message::getBody)
            .containsExactly(
                tuple(SenderType.BOT, ConversationService.GREETING),
                tuple(SenderType.CUSTOMER, "How do I reset my password?"));
    }

    @Test
    void sameClientMessageIdIsStoredOnce() {
        UUID id = service.startOrResume(TENANT, visitor).conversation().getId();

        Message first = service.addCustomerMessage(id, "c-1", "hello");
        Message retry = service.addCustomerMessage(id, "c-1", "hello");

        assertThat(retry.getId()).isEqualTo(first.getId());
        assertThat(service.messages(id, null, 50)).hasSize(2);
    }

    @Test
    void messagesAfterReturnsOnlyNewerOnes() {
        UUID id = service.startOrResume(TENANT, visitor).conversation().getId();
        Message one = service.addCustomerMessage(id, "c-1", "one");
        service.addCustomerMessage(id, "c-2", "two");

        List<Message> after = service.messages(id, one.getId(), 50);

        assertThat(after).extracting(Message::getBody).containsExactly("two");
    }

    @Test
    void emptyAndTooLongMessagesAreRejected() {
        UUID id = service.startOrResume(TENANT, visitor).conversation().getId();

        assertThatThrownBy(() -> service.addCustomerMessage(id, "c-1", "  "))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_ERROR"));
        assertThatThrownBy(() -> service.addCustomerMessage(id, "c-2", "x".repeat(Message.MAX_BODY_LENGTH + 1)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("MESSAGE_TOO_LONG"));
    }

    @Test
    void handoffMovesToWaitingWithReason() {
        UUID id = service.startOrResume(TENANT, visitor).conversation().getId();

        Conversation c = service.handoff(id, HandoffReason.CUSTOMER_REQUEST, SenderType.CUSTOMER);

        assertThat(c.getStatus()).isEqualTo(Status.WAITING);
        assertThat(c.getHandoffReason()).isEqualTo(HandoffReason.CUSTOMER_REQUEST);
        assertThat(c.getPriority()).isEqualTo(Priority.NORMAL);
        assertThat(service.messages(id, null, 50)).last()
            .satisfies(m -> assertThat(m.getSenderType()).isEqualTo(SenderType.SYSTEM));
        assertThat(service.events(id)).extracting(ConversationEvent::getType)
            .containsExactly(EventType.CREATED, EventType.HANDOFF);
    }

    @Test
    void urgentHandoffGetsTopPriority() {
        UUID id = service.startOrResume(TENANT, visitor).conversation().getId();

        assertThat(service.handoff(id, HandoffReason.URGENT, SenderType.BOT).getPriority())
            .isEqualTo(Priority.URGENT);
    }

    @Test
    void secondHandoffIsRejected() {
        UUID id = service.startOrResume(TENANT, visitor).conversation().getId();
        service.handoff(id, HandoffReason.CUSTOMER_REQUEST, SenderType.CUSTOMER);

        assertThatThrownBy(() -> service.handoff(id, HandoffReason.CUSTOMER_REQUEST, SenderType.CUSTOMER))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INVALID_TRANSITION"));
    }

    @Test
    void messageOnResolvedConversationReopensIt() {
        UUID id = service.startOrResume(TENANT, visitor).conversation().getId();
        service.handoff(id, HandoffReason.URGENT, SenderType.BOT);
        Conversation c = service.get(id);
        c.moveTo(StateMachine.next(c.getStatus(), Transition.RESOLVE), c.getUpdatedAt());

        service.addCustomerMessage(id, "c-9", "one more question");

        Conversation reopened = service.get(id);
        assertThat(reopened.getStatus()).isEqualTo(Status.BOT);
        assertThat(reopened.getPriority()).isEqualTo(Priority.NORMAL);
        assertThat(reopened.getHandoffReason()).isNull();
        assertThat(service.events(id)).extracting(ConversationEvent::getType).endsWith(EventType.REOPENED);
    }

    @Test
    void unknownConversationIsNotFound() {
        assertThatThrownBy(() -> service.get(UUID.randomUUID()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CONVERSATION_NOT_FOUND"));
    }

    @Test
    void databaseAllowsOnlyOneOpenConversationPerVisitor() {
        service.startOrResume(TENANT, visitor);

        assertThatThrownBy(() -> {
            conversations.saveAndFlush(Conversation.start(TENANT, visitor, Instant.now()));
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
