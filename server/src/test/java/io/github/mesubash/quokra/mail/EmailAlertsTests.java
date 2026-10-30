package io.github.mesubash.quokra.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.mesubash.quokra.TestcontainersConfiguration;
import io.github.mesubash.quokra.conversation.AgentRef;
import io.github.mesubash.quokra.conversation.ConversationService;
import io.github.mesubash.quokra.conversation.Enums.HandoffReason;
import io.github.mesubash.quokra.conversation.Enums.SenderType;
import io.github.mesubash.quokra.presence.AgentPresence;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"spring.mail.host=smtp.example.test", "quokra.mail.from=Support <support@example.test>"})
@Import(TestcontainersConfiguration.class)
@Transactional
class EmailAlertsTests {

    @MockitoBean
    JavaMailSender sender;

    @Autowired
    EmailAlerts alerts;

    @Autowired
    ConversationService service;

    @Autowired
    AgentPresence presence;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    UUID tenant = UUID.randomUUID();
    AgentRef sam = new AgentRef("sam-" + tenant, "Sam");

    @BeforeEach
    void setUp() {
        jdbc.update("""
            insert into tenants (id, slug, display_name, publishable_key, bot_config)
            values (?, ?, 'Acme', ?, 'test')
            """, tenant, "acme-" + tenant, "pk_" + tenant);
        jdbc.update("insert into tenant_agents (tenant_id, agent_sub, name, email) values (?, ?, 'Sam', 'sam@example.test')",
            tenant, sam.sub());
    }

    UUID waitingChat() {
        UUID id = service.startOrResume(tenant, UUID.randomUUID()).conversation().getId();
        service.addContact(id, "Pat", "pat-" + id + "@example.test");
        service.handoff(id, HandoffReason.CUSTOMER_REQUEST, SenderType.CUSTOMER);
        return id;
    }

    void sql(String update, Object... args) {
        entityManager.flush();
        jdbc.update(update, args);
        entityManager.clear();
    }

    SimpleMailMessage sent() {
        var mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(mail.capture());
        return mail.getValue();
    }

    @Test
    void tellsTheTeamOnceWhenAChatWaitsWithNobodyOnline() {
        UUID id = waitingChat();
        sql("update conversations set waiting_since = now() - interval '20 minutes' where id = ?", id);

        assertThat(alerts.alertWaiting()).isOne();
        assertThat(alerts.alertWaiting()).isZero();

        SimpleMailMessage mail = sent();
        assertThat(mail.getTo()).containsExactly("sam@example.test");
        assertThat(mail.getSubject()).isEqualTo("Acme: a chat is waiting");
        assertThat(mail.getText()).contains("Pat has been waiting for 20 minutes");
    }

    @Test
    void noEmailWhileSomeoneIsOnline() {
        UUID id = waitingChat();
        sql("update conversations set waiting_since = now() - interval '20 minutes' where id = ?", id);
        presence.checkIn(sam.sub(), AgentPresence.Status.ONLINE);

        assertThat(alerts.alertWaiting()).isZero();
        verify(sender, times(0)).send(any(SimpleMailMessage.class));
    }

    @Test
    void emailsTheCustomerAReplyTheyHaventSeen() {
        sql("update tenants set email_customers = true where id = ?", tenant);
        UUID id = waitingChat();
        service.accept(id, sam);
        service.addAgentMessage(id, sam, "a-1", "Your refund is on its way.");
        sql("update messages set created_at = now() - interval '10 minutes' where conversation_id = ?", id);

        assertThat(alerts.emailUnseenReplies()).isOne();
        assertThat(alerts.emailUnseenReplies()).isZero();

        SimpleMailMessage mail = sent();
        assertThat(mail.getTo()).containsExactly("pat-" + id + "@example.test");
        assertThat(mail.getFrom()).isEqualTo("Support <support@example.test>");
        assertThat(mail.getText()).contains("Hi Pat,").contains("Sam: Your refund is on its way.");
    }

    @Test
    void customersAreOnlyEmailedWhenTheCompanyTurnedItOn() {
        UUID id = waitingChat();
        service.accept(id, sam);
        service.addAgentMessage(id, sam, "a-1", "Hello");
        sql("update messages set created_at = now() - interval '10 minutes' where conversation_id = ?", id);

        assertThat(alerts.emailUnseenReplies()).isZero();
    }
}
