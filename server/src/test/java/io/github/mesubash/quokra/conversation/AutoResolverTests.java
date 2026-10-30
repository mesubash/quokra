package io.github.mesubash.quokra.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mesubash.quokra.TestcontainersConfiguration;
import io.github.mesubash.quokra.conversation.Enums.HandoffReason;
import io.github.mesubash.quokra.conversation.Enums.SenderType;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AutoResolverTests {

    @Autowired
    AutoResolver resolver;

    @Autowired
    ConversationService service;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    UUID tenant = UUID.randomUUID();

    @BeforeEach
    void addTenant() {
        jdbc.update("""
            insert into tenants (id, slug, display_name, publishable_key, bot_config)
            values (?, ?, 'Acme', ?, 'test')
            """, tenant, "acme-" + tenant, "pk_" + tenant);
    }

    UUID chat() {
        return service.startOrResume(tenant, UUID.randomUUID()).conversation().getId();
    }

    UUID withAgent(String lastWriter) {
        UUID id = chat();
        service.addContact(id, "Pat", "pat-" + id + "@example.test");
        service.handoff(id, HandoffReason.CUSTOMER_REQUEST, SenderType.CUSTOMER);
        AgentRef sam = new AgentRef("sam", "Sam");
        service.accept(id, sam);
        if (lastWriter.equals("CUSTOMER")) {
            service.addAgentMessage(id, sam, "a-1", "How can I help?");
            service.addCustomerMessage(id, "c-9", "Here's my question", null);
        } else {
            service.addCustomerMessage(id, "c-9", "Here's my question", null);
            service.addAgentMessage(id, sam, "a-1", "Done, anything else?");
        }
        return id;
    }

    // Pretends the last message was this many hours ago.
    void quietFor(UUID id, int hours) {
        entityManager.flush();
        jdbc.update("update conversations set last_message_at = now() - make_interval(hours => ?) where id = ?", hours, id);
        entityManager.clear();
    }

    Status status(UUID id) {
        entityManager.flush();
        entityManager.clear();
        return service.get(id).getStatus();
    }

    @Test
    void closesAQuietChatWithTheAgentWhenTheAgentWroteLast() {
        UUID id = withAgent("AGENT");
        quietFor(id, 25);

        resolver.run();

        assertThat(status(id)).isEqualTo(Status.RESOLVED);
        assertThat(service.messages(id, null, 50).getLast().getBody()).startsWith("We've closed this chat");
        assertThat(service.events(id).getLast().getReason()).isEqualTo(ConversationService.AUTO_RESOLVED);
    }

    @Test
    void leavesItOpenWhenTheCustomerIsWaitingForAnAnswer() {
        UUID id = withAgent("CUSTOMER");
        quietFor(id, 25);

        resolver.run();

        assertThat(status(id)).isEqualTo(Status.HUMAN);
    }

    @Test
    void closesABotChatTheCustomerLeftButNotOneWithOnlyTheGreeting() {
        UUID talked = chat();
        service.addCustomerMessage(talked, "c-1", "price", null);
        UUID onlyGreeting = chat();
        quietFor(talked, 25);
        quietFor(onlyGreeting, 25);

        resolver.run();

        assertThat(status(talked)).isEqualTo(Status.RESOLVED);
        assertThat(status(onlyGreeting)).isEqualTo(Status.BOT);
    }

    @Test
    void neverClosesWaitingChats() {
        UUID id = chat();
        service.addContact(id, "Pat", "pat@example.test");
        service.handoff(id, HandoffReason.CUSTOMER_REQUEST, SenderType.CUSTOMER);
        quietFor(id, 100);

        resolver.run();

        assertThat(status(id)).isEqualTo(Status.WAITING);
    }

    @Test
    void waitsForTheTenantsDelayAndCanBeTurnedOff() {
        UUID recent = withAgent("AGENT");
        quietFor(recent, 23);
        resolver.run();
        assertThat(status(recent)).isEqualTo(Status.HUMAN);

        jdbc.update("update tenants set auto_resolve_minutes = 0 where id = ?", tenant);
        quietFor(recent, 200);
        resolver.run();
        assertThat(status(recent)).isEqualTo(Status.HUMAN);

        jdbc.update("update tenants set auto_resolve_minutes = 60 where id = ?", tenant);
        quietFor(recent, 2);
        resolver.run();
        assertThat(status(recent)).isEqualTo(Status.RESOLVED);
    }

    @Test
    void theCustomerWritingAgainReopensIt() {
        UUID id = withAgent("AGENT");
        quietFor(id, 25);
        resolver.run();

        service.addCustomerMessage(id, "c-10", "Actually, one more thing", null);

        assertThat(status(id)).isEqualTo(Status.BOT);
    }
}
