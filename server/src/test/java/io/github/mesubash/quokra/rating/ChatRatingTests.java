package io.github.mesubash.quokra.rating;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mesubash.quokra.TestcontainersConfiguration;
import io.github.mesubash.quokra.conversation.AgentRef;
import io.github.mesubash.quokra.conversation.ConversationService;
import io.github.mesubash.quokra.conversation.Enums.HandoffReason;
import io.github.mesubash.quokra.conversation.Enums.SenderType;
import io.github.mesubash.quokra.security.DevAgentTokens;
import io.github.mesubash.quokra.session.SessionTokens;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
    "quokra.dev.agent-tokens=true",
    "quokra.admins.company-claim=permissions",
    "quokra.admins.company-value=quokra:{tenant}:admin"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ChatRatingTests {

    static final String RATING = "/api/v1/widget/conversation/rating";
    static final AgentRef SAM = new AgentRef("sam", "Sam");
    static final AgentRef KIM = new AgentRef("kim", "Kim");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionTokens sessionTokens;

    @Autowired
    ConversationService service;

    @Autowired
    DevAgentTokens devTokens;

    UUID acme = UUID.randomUUID();
    UUID globex = UUID.randomUUID();

    @BeforeEach
    void tenants() {
        jdbc.update("""
            insert into tenants (id, slug, display_name, publishable_key, bot_config)
            values (?, 'acme', 'Acme', 'pk_test_acme', 'test'), (?, 'globex', 'Globex', 'pk_test_globex', 'test')
            """, acme, globex);
    }

    record Chat(UUID id, String token) {}

    // A guest's chat, taken by `agent` (null: closed from the queue, never with an agent), and closed if asked.
    Chat chat(AgentRef agent, boolean resolve) {
        UUID visitor = UUID.randomUUID();
        UUID id = service.startOrResume(acme, visitor).conversation().getId();
        service.addContact(id, "Pat", "pat@example.test");
        service.handoff(id, HandoffReason.CUSTOMER_REQUEST, SenderType.CUSTOMER);
        if (agent != null) {
            service.accept(id, agent);
        }
        if (resolve) {
            service.resolve(id, agent != null ? agent : SAM);
        }
        return new Chat(id, sessionTokens.issue(acme, visitor, null).token());
    }

    MvcTestResult rate(String token, String json) {
        return mvc.post().uri(RATING).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    MvcTestResult get(String token, String uri) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    @Test
    void customerRatesThenAddsAComment() {
        Chat chat = chat(SAM, true);
        assertThat(get(chat.token(), "/api/v1/widget/conversation")).bodyJson()
            .extractingPath("$.conversation.canRate").isEqualTo(true);

        assertThat(rate(chat.token(), "{\"positive\":true}")).hasStatusOk().bodyJson()
            .extractingPath("$.conversation.rating.positive").isEqualTo(true);
        assertThat(rate(chat.token(), "{\"positive\":true,\"comment\":\"  Quick and kind  \"}")).hasStatusOk()
            .bodyJson().extractingPath("$.conversation.rating.comment").isEqualTo("Quick and kind");

        var after = get(chat.token(), "/api/v1/widget/conversation");
        assertThat(after).bodyJson().extractingPath("$.conversation.canRate").isEqualTo(false);
        assertThat(after).bodyJson().extractingPath("$.conversation.rating.comment").isEqualTo("Quick and kind");
        assertThat(jdbc.queryForObject("select agent_sub from chat_ratings where conversation_id = ?", String.class,
            chat.id())).isEqualTo("sam");
    }

    @Test
    void onlyClosedChatsWithAnAgent() {
        assertThat(rate(chat(SAM, false).token(), "{\"positive\":true}")).hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.error.code").isEqualTo("NOT_RESOLVED");

        Chat botOnly = chat(null, true);
        assertThat(get(botOnly.token(), "/api/v1/widget/conversation")).bodyJson()
            .extractingPath("$.conversation.canRate").isEqualTo(false);
        assertThat(rate(botOnly.token(), "{\"positive\":false}")).hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.error.code").isEqualTo("NO_AGENT");

        // Someone else's session reaches only their own chat, and has none.
        String stranger = sessionTokens.issue(acme, UUID.randomUUID(), null).token();
        assertThat(rate(stranger, "{\"positive\":true}")).hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.error.code").isEqualTo("NO_OPEN_CONVERSATION");

        Chat chat = chat(SAM, true);
        assertThat(rate(chat.token(), "{}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(rate(chat.token(), "{\"positive\":true,\"comment\":\"" + "x".repeat(1001) + "\"}"))
            .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void oneRatingAndOneComment() {
        Chat chat = chat(SAM, true);
        assertThat(rate(chat.token(), "{\"positive\":true}")).hasStatusOk();
        // The thumb can't change, and there's nothing new without a comment.
        assertThat(rate(chat.token(), "{\"positive\":false,\"comment\":\"Changed my mind\"}"))
            .hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.error.code").isEqualTo("ALREADY_RATED");
        assertThat(rate(chat.token(), "{\"positive\":true}")).hasStatus(HttpStatus.CONFLICT);
        assertThat(rate(chat.token(), "{\"positive\":true,\"comment\":\"Thanks\"}")).hasStatusOk();
        assertThat(rate(chat.token(), "{\"positive\":true,\"comment\":\"Again\"}")).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void theTeamSeesTheRatingOnTheChat() throws Exception {
        Chat chat = chat(SAM, true);
        rate(chat.token(), "{\"positive\":false,\"comment\":\"Too slow\"}");
        String root = devTokens.sign("root", "Root", List.of("QUOKRA_ADMIN"));

        assertThat(get(root, "/api/v1/agent/conversations/" + chat.id())).bodyJson().satisfies(d -> {
            assertThat(d).extractingPath("$.rating.positive").isEqualTo(false);
            assertThat(d).extractingPath("$.rating.comment").isEqualTo("Too slow");
        });
        assertThat(get(root, "/api/v1/agent/conversations?status=RESOLVED")).bodyJson()
            .extractingPath("$.items[?(@.id == '" + chat.id() + "')].rating").asArray().containsExactly(false);
    }

    @Test
    void adminsSeeTheSummary() throws Exception {
        rate(chat(SAM, true).token(), "{\"positive\":true,\"comment\":\"Great\"}");
        rate(chat(SAM, true).token(), "{\"positive\":false}");
        rate(chat(KIM, true).token(), "{\"positive\":true,\"comment\":\"Fast\"}");
        Chat old = chat(KIM, true);
        rate(old.token(), "{\"positive\":false,\"comment\":\"Old\"}");
        jdbc.update("update chat_ratings set created_at = now() - interval '40 days' where conversation_id = ?", old.id());
        jdbc.update("update chat_ratings set created_at = now() - interval '1 minute' where comment = 'Great'");

        String root = devTokens.sign("root", "Root", List.of("QUOKRA_ADMIN"));
        var summary = get(root, "/api/v1/admin/tenants/acme/ratings");
        assertThat(summary).hasStatusOk();
        assertThat(summary).bodyJson().extractingPath("$.total").isEqualTo(3);
        assertThat(summary).bodyJson().extractingPath("$.positive").isEqualTo(2);
        assertThat(summary).bodyJson().extractingPath("$.percentPositive").isEqualTo(67);
        assertThat(summary).bodyJson().extractingPath("$.agents[0]")
            .isEqualTo(Map.of("sub", "sam", "name", "Sam", "positive", 1, "total", 2));
        assertThat(summary).bodyJson().extractingPath("$.recent[*].comment").isEqualTo(List.of("Fast", "Great"));
        assertThat(summary).bodyJson().extractingPath("$.recent[0].customerName").isEqualTo("Pat");

        assertThat(get(root, "/api/v1/admin/tenants/acme/ratings?days=60")).bodyJson()
            .extractingPath("$.total").isEqualTo(4);
        assertThat(get(root, "/api/v1/admin/tenants/globex/ratings")).bodyJson()
            .extractingPath("$.percentPositive").isNull();
    }

    @Test
    void onlyThatCompanysAdmins() throws Exception {
        String globexAdmin = devTokens.sign("lee", "Lee", List.of(), null,
            Map.of("permissions", List.of("quokra:globex:admin")));
        assertThat(get(globexAdmin, "/api/v1/admin/tenants/globex/ratings")).hasStatusOk();
        assertThat(get(globexAdmin, "/api/v1/admin/tenants/acme/ratings")).hasStatus(HttpStatus.FORBIDDEN);

        String agent = devTokens.sign("sam", "Sam", List.of("SUPPORT_AGENT"));
        assertThat(get(agent, "/api/v1/admin/tenants/acme/ratings")).hasStatus(HttpStatus.FORBIDDEN);
    }
}
