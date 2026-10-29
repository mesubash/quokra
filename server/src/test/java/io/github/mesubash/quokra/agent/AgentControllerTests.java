package io.github.mesubash.quokra.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mesubash.quokra.TestcontainersConfiguration;
import io.github.mesubash.quokra.conversation.ConversationService;
import io.github.mesubash.quokra.conversation.Enums.HandoffReason;
import io.github.mesubash.quokra.conversation.Enums.SenderType;
import io.github.mesubash.quokra.security.DevAgentTokens;
import java.util.List;
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

@SpringBootTest(properties = "quokra.dev.agent-tokens=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class AgentControllerTests {

    static final String BASE = "/api/v1/agent";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ConversationService service;

    @Autowired
    DevAgentTokens devTokens;

    UUID tenant = UUID.randomUUID();
    UUID otherTenant = UUID.randomUUID();
    String alice;
    String bob;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("""
            insert into tenants (id, slug, display_name, publishable_key, bot_config)
            values (?, 'acme', 'Acme Inc', 'pk_test_acme', 'test'),
                   (?, 'globex', 'Globex', 'pk_test_globex', 'test')
            """, tenant, otherTenant);
        jdbc.update("""
            insert into tenant_agents (tenant_id, agent_sub)
            values (?, 'alice'), (?, 'bob'), (?, 'carol')
            """, tenant, tenant, otherTenant);
        alice = devTokens.sign("alice", "Alice", List.of("SUPPORT_AGENT"));
        bob = devTokens.sign("bob", "Bob", List.of("SUPPORT_AGENT"));
    }

    UUID waiting(UUID tenantId, HandoffReason reason) {
        UUID id = service.startOrResume(tenantId, UUID.randomUUID()).conversation().getId();
        service.addContact(id, "Pat", "pat-" + id + "@example.test");
        service.handoff(id, reason, SenderType.CUSTOMER);
        return id;
    }

    MvcTestResult get(String token, String uri) {
        return mvc.get().uri(BASE + uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    MvcTestResult post(String token, String uri, String json) {
        var request = mvc.post().uri(BASE + uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (json != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return request.exchange();
    }

    @Test
    void requiresAToken() {
        assertThat(mvc.get().uri(BASE + "/me")).hasStatus(HttpStatus.UNAUTHORIZED)
            .bodyJson().extractingPath("$.error.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void requiresTheAgentRole() throws Exception {
        String noRole = devTokens.sign("alice", "Alice", List.of("SOMETHING_ELSE"));

        assertThat(get(noRole, "/me")).hasStatus(HttpStatus.FORBIDDEN)
            .bodyJson().extractingPath("$.error.code").isEqualTo("FORBIDDEN");
    }

    @Test
    void meListsMyTenants() {
        MvcTestResult result = get(alice, "/me");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("Alice");
        assertThat(result).bodyJson().extractingPath("$.tenants").isEqualTo(List.of("acme"));
    }

    @Test
    void queueShowsUrgentFirstAndOnlyMyTenants() {
        UUID normal = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);
        UUID urgent = waiting(tenant, HandoffReason.URGENT);
        waiting(otherTenant, HandoffReason.URGENT);

        MvcTestResult result = get(alice, "/conversations?status=WAITING");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.items[*].id")
            .isEqualTo(List.of(urgent.toString(), normal.toString()));
        assertThat(result).bodyJson().extractingPath("$.items[0].lastMessagePreview")
            .isEqualTo("Handoff message.");
    }

    @Test
    void otherTenantsConversationsLookMissing() {
        UUID theirs = waiting(otherTenant, HandoffReason.CUSTOMER_REQUEST);

        assertThat(get(alice, "/conversations/" + theirs)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(post(alice, "/conversations/" + theirs + "/accept", null)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void acceptTakesTheConversation() {
        UUID id = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);

        MvcTestResult result = post(alice, "/conversations/" + id + "/accept", null);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.conversation.status").isEqualTo("HUMAN");
        assertThat(result).bodyJson().extractingPath("$.conversation.assignee.name").isEqualTo("Alice");
        assertThat(service.messages(id, null, 50).getLast().getBody()).isEqualTo("Alice joined the chat");
    }

    @Test
    void secondAgentCannotAcceptToo() {
        UUID id = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);
        post(alice, "/conversations/" + id + "/accept", null);

        assertThat(post(bob, "/conversations/" + id + "/accept", null)).hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.error.code").isEqualTo("ALREADY_ACCEPTED");
    }

    @Test
    void onlyTheAssigneeCanReply() {
        UUID id = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);
        post(alice, "/conversations/" + id + "/accept", null);

        assertThat(post(bob, "/conversations/" + id + "/messages", "{\"body\":\"Hi\"}"))
            .hasStatus(HttpStatus.FORBIDDEN).bodyJson().extractingPath("$.error.code").isEqualTo("NOT_ASSIGNEE");

        MvcTestResult reply = post(alice, "/conversations/" + id + "/messages",
            "{\"clientMessageId\":\"a-1\",\"body\":\"Hi, I'm Alice\"}");
        assertThat(reply).hasStatus(HttpStatus.CREATED);
        assertThat(reply).bodyJson().extractingPath("$.message.senderType").isEqualTo("AGENT");
        assertThat(reply).bodyJson().extractingPath("$.message.senderName").isEqualTo("Alice");
    }

    @Test
    void replyingBeforeAcceptingIsAConflict() {
        UUID id = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);

        assertThat(post(alice, "/conversations/" + id + "/messages", "{\"body\":\"Hi\"}"))
            .hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void customerRepliesDontWakeTheBot() {
        UUID id = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);
        post(alice, "/conversations/" + id + "/accept", null);

        service.addCustomerMessage(id, "c-1", "price", null);

        assertThat(service.messages(id, null, 50).getLast().getSenderType()).isEqualTo(SenderType.CUSTOMER);
    }

    @Test
    void assigneeResolves() {
        UUID id = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);
        post(alice, "/conversations/" + id + "/accept", null);

        assertThat(post(bob, "/conversations/" + id + "/resolve", null)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(post(alice, "/conversations/" + id + "/resolve", null)).hasStatusOk()
            .bodyJson().extractingPath("$.conversation.status").isEqualTo("RESOLVED");
    }

    @Test
    void anyAgentCanCloseAWaitingConversation() {
        UUID id = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);

        assertThat(post(bob, "/conversations/" + id + "/resolve", null)).hasStatusOk();
    }

    @Test
    void returnToBotHandsBackWithTheMenu() {
        UUID id = waiting(tenant, HandoffReason.URGENT);
        post(alice, "/conversations/" + id + "/accept", null);

        MvcTestResult result = post(alice, "/conversations/" + id + "/return-to-bot", null);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.conversation.status").isEqualTo("BOT");
        assertThat(result).bodyJson().extractingPath("$.conversation.assignee").isNull();
        assertThat(result).bodyJson().extractingPath("$.conversation.priority").isEqualTo("NORMAL");
        var last = service.messages(id, null, 50).getLast();
        assertThat(last.getSenderType()).isEqualTo(SenderType.BOT);
        assertThat(last.getOptions()).isNotEmpty();
    }

    @Test
    void detailIncludesHistoryAndTimeline() {
        UUID id = waiting(tenant, HandoffReason.URGENT);
        post(alice, "/conversations/" + id + "/accept", null);

        MvcTestResult result = get(alice, "/conversations/" + id);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.conversation.handoffReason").isEqualTo("URGENT");
        assertThat(result).bodyJson().extractingPath("$.events[*].type")
            .isEqualTo(List.of("CREATED", "CONTACT_ADDED", "HANDOFF", "ACCEPTED"));
        assertThat(result).bodyJson().extractingPath("$.events[3].actorName").isEqualTo("Alice");
    }

    @Test
    void myChatsListsWhatIAccepted() {
        UUID mine = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);
        UUID bobs = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);
        post(alice, "/conversations/" + mine + "/accept", null);
        post(bob, "/conversations/" + bobs + "/accept", null);

        assertThat(get(alice, "/conversations?status=HUMAN&assignee=me")).hasStatusOk()
            .bodyJson().extractingPath("$.items[*].id").isEqualTo(List.of(mine.toString()));
    }
    @Test
    void localDashboardsCanFetchDevTokens() {
        assertThat(mvc.options().uri("/dev/agent-token")
                .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
            .hasStatusOk()
            .hasHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5500");
    }

    @Test
    void otherSitesCantFetchDevTokens() {
        assertThat(mvc.options().uri("/dev/agent-token")
                .header(HttpHeaders.ORIGIN, "https://evil.test")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
            .hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void agentApiDoesNotAllowOtherOrigins() {
        assertThat(mvc.options().uri(BASE + "/conversations")
                .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
            .doesNotContainHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }
}
