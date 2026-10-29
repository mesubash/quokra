package io.github.mesubash.quokra.conversation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mesubash.quokra.TestcontainersConfiguration;
import io.github.mesubash.quokra.conversation.Enums.HandoffReason;
import io.github.mesubash.quokra.conversation.Enums.SenderType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

// Two agents accepting at the same moment: the version column makes the second write fail.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AcceptRaceTests {

    @Autowired
    ConversationService service;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    @Test
    void acceptLosesIfSomeoneElseChangedTheConversationFirst() {
        UUID tenant = UUID.randomUUID();
        jdbc.update("""
            insert into tenants (id, slug, display_name, publishable_key, bot_config)
            values (?, ?, 'Race', ?, 'test')
            """, tenant, "race-" + tenant, "pk_race_" + tenant);
        UUID id = service.startOrResume(tenant, UUID.randomUUID()).conversation().getId();
        service.addContact(id, "Pat", "pat@example.test");
        service.handoff(id, HandoffReason.CUSTOMER_REQUEST, SenderType.CUSTOMER);

        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            service.get(id); // our copy, at the current version
            jdbc.update("update conversations set version = version + 1 where id = ?", id); // the other agent wins
            service.accept(id, new AgentRef("bob", "Bob"));
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
