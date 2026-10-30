package io.github.mesubash.quokra.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mesubash.quokra.TestcontainersConfiguration;
import io.github.mesubash.quokra.attachment.FileStore;
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
class RetentionJobTests {

    @Autowired
    RetentionJob job;

    @Autowired
    ConversationService service;

    @Autowired
    FileStore files;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    UUID tenant = UUID.randomUUID();
    AgentRef sam = new AgentRef("sam", "Sam");

    @BeforeEach
    void addTenant() {
        jdbc.update("""
            insert into tenants (id, slug, display_name, publishable_key, bot_config, retention_months)
            values (?, ?, 'Acme', ?, 'test', 3)
            """, tenant, "acme-" + tenant, "pk_" + tenant);
    }

    // A chat with the team, resolved, last written to this many months ago.
    UUID resolvedChat(int monthsAgo) {
        UUID id = service.startOrResume(tenant, UUID.randomUUID()).conversation().getId();
        service.addContact(id, "Pat", "pat-" + id + "@example.test");
        service.handoff(id, HandoffReason.CUSTOMER_REQUEST, SenderType.CUSTOMER);
        service.accept(id, sam);
        service.addAgentMessage(id, sam, "a-" + id, "Here you go");
        service.resolve(id, sam);
        ago(id, monthsAgo);
        return id;
    }

    void ago(UUID id, int months) {
        entityManager.flush();
        jdbc.update("update conversations set last_message_at = now() - make_interval(months => ?) where id = ?",
            months, id);
        entityManager.clear();
    }

    int count(String table, UUID conversation) {
        String column = table.equals("conversations") ? "id" : "conversation_id";
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + " = ?", Integer.class,
            conversation);
    }

    @Test
    void deletesOldResolvedChatsWithEverythingInThem() {
        UUID old = resolvedChat(4);
        UUID recent = resolvedChat(2);
        String image = "test/" + old + "/shot";
        files.put(image, new byte[] {1}, "image/png");
        entityManager.flush();
        jdbc.update("update messages set attachment_key = ? where conversation_id = ? and sender_type = 'AGENT'",
            image, old);
        service.addNote(old, sam, "VIP");
        entityManager.flush();

        assertThat(job.run()).isEqualTo(1);

        for (String table : new String[] {"conversations", "messages", "conversation_events", "conversation_notes"}) {
            assertThat(count(table, old)).as(table).isZero();
        }
        assertThat(count("conversations", recent)).isOne();
        assertThatThrownBy(() -> files.get(image)).isInstanceOf(RuntimeException.class);
        // The contact stays, so a block would too.
        assertThat(jdbc.queryForObject("select count(*) from contacts where email = ?", Integer.class,
            "pat-" + old + "@example.test")).isOne();
    }

    @Test
    void neverDeletesOpenChatsHoweverOld() {
        UUID open = service.startOrResume(tenant, UUID.randomUUID()).conversation().getId();
        ago(open, 12);
        assertThat(job.run()).isZero();
        assertThat(count("conversations", open)).isOne();
    }
}
