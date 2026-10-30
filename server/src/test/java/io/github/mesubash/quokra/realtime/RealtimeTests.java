package io.github.mesubash.quokra.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import io.github.mesubash.quokra.TestcontainersConfiguration;
import io.github.mesubash.quokra.security.DevAgentTokens;
import io.github.mesubash.quokra.session.SessionTokens;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

// Events are sent after commit, so this runs against a real server without test transactions.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "quokra.dev.agent-tokens=true")
@Import(TestcontainersConfiguration.class)
class RealtimeTests {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionTokens sessionTokens;

    @Autowired
    DevAgentTokens devTokens;

    UUID tenantId = UUID.randomUUID();
    UUID otherTenantId = UUID.randomUUID();
    String agentSub = "agent-" + tenantId;
    String otherAgentSub = "agent-" + otherTenantId;
    WebSocketStompClient stomp;
    RestClient http;
    RestClient agentHttp;

    @BeforeEach
    void setUp() {
        String key = "pk_test_" + tenantId;
        jdbc.update("""
            insert into tenants (id, slug, display_name, publishable_key, bot_config)
            values (?, ?, 'Realtime Test', ?, 'test')
            """, tenantId, "rt-" + tenantId, key);
        jdbc.update("""
            insert into tenants (id, slug, display_name, publishable_key, bot_config)
            values (?, ?, 'Other', ?, 'test')
            """, otherTenantId, "rt-" + otherTenantId, "pk_test_" + otherTenantId);
        jdbc.update("insert into tenant_agents (tenant_id, agent_sub) values (?, ?), (?, ?)",
            tenantId, agentSub, otherTenantId, otherAgentSub);
        stomp = new WebSocketStompClient(new StandardWebSocketClient());
        stomp.setMessageConverter(new CompositeMessageConverter(
            List.of(new StringMessageConverter(), new JacksonJsonMessageConverter())));
        http = RestClient.create("http://localhost:" + port + "/api/v1/widget");
        agentHttp = RestClient.create("http://localhost:" + port + "/api/v1/agent");
    }

    @AfterEach
    void stop() {
        stomp.stop();
    }

    @Test
    void customerReceivesTheirMessageAndTheBotReplyLive() throws Exception {
        String token = sessionTokens.issue(tenantId).token();
        String conversationId = startConversation(token);
        BlockingQueue<Object> events = subscribe(connect(token), conversationId);

        send(token, "how much does it cost");

        Object first = events.poll(5, TimeUnit.SECONDS);
        Object second = events.poll(5, TimeUnit.SECONDS);
        assertThat(JsonPath.<String>read(first, "$.type")).isEqualTo("message.created");
        assertThat(JsonPath.<String>read(first, "$.data.senderType")).isEqualTo("CUSTOMER");
        assertThat(JsonPath.<String>read(second, "$.data.senderType")).isEqualTo("BOT");
        assertThat(JsonPath.<String>read(second, "$.data.body")).isEqualTo("Pricing answer.");
        assertThat(JsonPath.<String>read(second, "$.conversationId")).isEqualTo(conversationId);
    }

    @Test
    void handoffSendsAStatusUpdate() throws Exception {
        String token = sessionTokens.issue(tenantId).token();
        String conversationId = startConversation(token);
        BlockingQueue<Object> events = subscribe(connect(token), conversationId);

        http.post().uri("/conversation/handoff").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .retrieve().toBodilessEntity();

        Object message = events.poll(5, TimeUnit.SECONDS);
        Object update = events.poll(5, TimeUnit.SECONDS);
        assertThat(JsonPath.<String>read(message, "$.data.senderType")).isEqualTo("SYSTEM");
        assertThat(JsonPath.<String>read(update, "$.type")).isEqualTo("conversation.updated");
        assertThat(JsonPath.<String>read(update, "$.data.status")).isEqualTo("WAITING");
    }

    @Test
    void connectWithoutAValidTokenFails() {
        assertThatThrownBy(() -> connect("not-a-token")).isInstanceOf(ExecutionException.class);
    }

    @Test
    void cannotSubscribeToAnotherVisitorsConversation() throws Exception {
        String owner = sessionTokens.issue(tenantId).token();
        String conversationId = startConversation(owner);
        String stranger = sessionTokens.issue(tenantId).token();
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();

        StompSession session = connect(stranger, errors);
        BlockingQueue<Object> events = subscribe(session, conversationId);
        send(owner, "how much does it cost");

        assertThat(errors.poll(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(events.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void activityNamesTheAssigneeAndTheCustomer() throws Exception {
        String customer = sessionTokens.issue(tenantId).token();
        String conversationId = startConversation(customer);
        jdbc.update("update conversations set customer_name = 'Pat' where id = ?::uuid", conversationId);
        BlockingQueue<Object> queue = subscribeTo(connect(agentToken(agentSub)), queueTopic(tenantId));

        send(customer, "hello there");

        Object event = queue.poll(5, TimeUnit.SECONDS);
        assertThat(JsonPath.<String>read(event, "$.type")).isEqualTo("conversation.activity");
        assertThat(JsonPath.<String>read(event, "$.data.customerName")).isEqualTo("Pat");
        assertThat((Object) JsonPath.read(event, "$.data.assigneeSub")).isNull();
    }

    @Test
    void agentSeesNewWaitingConversationsLive() throws Exception {
        String customer = sessionTokens.issue(tenantId).token();
        startConversation(customer);
        BlockingQueue<Object> queue = subscribeTo(connect(agentToken(agentSub)), queueTopic(tenantId));

        send(customer, "our payment failed");

        Object event = nextOtherThan(queue, "conversation.activity");
        assertThat(JsonPath.<String>read(event, "$.type")).isEqualTo("conversation.waiting");
        assertThat(JsonPath.<String>read(event, "$.data.status")).isEqualTo("WAITING");
        assertThat(JsonPath.<String>read(event, "$.data.priority")).isEqualTo("URGENT");
        assertThat(JsonPath.<String>read(event, "$.data.handoffReason")).isEqualTo("URGENT");
    }

    @Test
    void agentCanFollowAConversationOfTheirTenant() throws Exception {
        String customer = sessionTokens.issue(tenantId).token();
        String conversationId = startConversation(customer);
        BlockingQueue<Object> events = subscribe(connect(agentToken(agentSub)), conversationId);

        send(customer, "how much does it cost");

        assertThat(JsonPath.<String>read(events.poll(5, TimeUnit.SECONDS), "$.data.senderType")).isEqualTo("CUSTOMER");
    }

    @Test
    void typingReachesTheOtherSideAndIsNotStored() throws Exception {
        String customer = sessionTokens.issue(tenantId).token();
        String conversationId = startConversation(customer);
        BlockingQueue<Object> agentSees = subscribe(connect(agentToken(agentSub)), conversationId);
        BlockingQueue<Object> customerSees = subscribe(connect(customer), conversationId);

        http.post().uri("/conversation/typing").header(HttpHeaders.AUTHORIZATION, "Bearer " + customer)
            .retrieve().toBodilessEntity();
        assertThat(typingFrom(agentSees, "CUSTOMER")).isNotNull();

        agentHttp.post().uri("/conversations/" + conversationId + "/typing")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + agentToken(agentSub)).retrieve().toBodilessEntity();
        // Both sides get every typing event (each ignores its own), so look for the agent's.
        assertThat(typingFrom(customerSees, "AGENT")).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from messages where conversation_id = ?::uuid", Integer.class,
            conversationId)).isEqualTo(1);
    }

    @Test
    void agentOfAnotherTenantCannotWatchTheQueue() throws Exception {
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        StompSession session = connect(agentToken(otherAgentSub), errors);

        subscribeTo(session, queueTopic(tenantId));

        assertThat(errors.poll(5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void newMessagesShowUpInTheQueueAsActivity() throws Exception {
        String customer = sessionTokens.issue(tenantId).token();
        String conversationId = startConversation(customer);
        BlockingQueue<Object> queue = subscribeTo(connect(agentToken(agentSub)), queueTopic(tenantId));

        send(customer, "hello there");

        Object event = queue.poll(5, TimeUnit.SECONDS);
        assertThat(JsonPath.<String>read(event, "$.type")).isEqualTo("conversation.activity");
        assertThat(JsonPath.<String>read(event, "$.data.id")).isEqualTo(conversationId);
        assertThat(JsonPath.<String>read(event, "$.data.lastMessagePreview")).isEqualTo("hello there");
        assertThat(JsonPath.<String>read(event, "$.data.senderType")).isEqualTo("CUSTOMER");
    }

    private static Object nextOtherThan(BlockingQueue<Object> queue, String type) throws InterruptedException {
        while (true) {
            Object event = queue.poll(5, TimeUnit.SECONDS);
            if (event == null || !type.equals(JsonPath.read(event, "$.type"))) {
                return event;
            }
        }
    }

    @Test
    void customerCannotReadAgentNotes() throws Exception {
        String customer = sessionTokens.issue(tenantId).token();
        String conversationId = startConversation(customer);
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();

        subscribeTo(connect(customer, errors), "/topic/conversations/" + conversationId + "/notes");

        assertThat(errors.poll(5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void agentsSeeNotesLive() throws Exception {
        String customer = sessionTokens.issue(tenantId).token();
        String conversationId = startConversation(customer);
        String token = agentToken(agentSub);
        BlockingQueue<Object> notes = subscribeTo(connect(token), "/topic/conversations/" + conversationId + "/notes");

        agentHttp.post().uri("/conversations/" + conversationId + "/notes")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
            .body("{\"body\":\"Called them back\"}").retrieve().toBodilessEntity();

        Object event = notes.poll(5, TimeUnit.SECONDS);
        assertThat(JsonPath.<String>read(event, "$.type")).isEqualTo("note.created");
        assertThat(JsonPath.<String>read(event, "$.data.body")).isEqualTo("Called them back");
    }

    @Test
    void customerCannotWatchTheQueue() throws Exception {
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        StompSession session = connect(sessionTokens.issue(tenantId).token(), errors);

        subscribeTo(session, queueTopic(tenantId));

        assertThat(errors.poll(5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void agentTokenWithoutTheRoleCannotConnect() throws Exception {
        String noRole = devTokens.sign(agentSub, "Agent", List.of("SOMETHING_ELSE"));

        assertThatThrownBy(() -> connect(noRole)).isInstanceOf(ExecutionException.class);
    }

    private String agentToken(String sub) throws Exception {
        return devTokens.sign(sub, "Agent", List.of("SUPPORT_AGENT"));
    }

    private static String queueTopic(UUID tenant) {
        return "/topic/tenants/" + tenant + "/queue";
    }

    // The next "typing" event from this sender within a few seconds, or null.
    private static Object typingFrom(BlockingQueue<Object> events, String senderType) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (long left; (left = until - System.nanoTime()) > 0; ) {
            Object event = events.poll(left, TimeUnit.NANOSECONDS);
            if (event != null && "typing".equals(JsonPath.read(event, "$.type"))
                    && senderType.equals(JsonPath.read(event, "$.data.senderType"))) {
                return event;
            }
        }
        return null;
    }

    private String startConversation(String token) {
        String body = http.post().uri("/conversation").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .retrieve().body(String.class);
        String id = JsonPath.read(body, "$.conversation.id");
        // A known contact, so handoffs go straight to the queue.
        UUID contact = UUID.randomUUID();
        jdbc.update("insert into contacts (id, tenant_id, email, created_at, updated_at) values (?, ?, ?, now(), now())",
            contact, tenantId, contact + "@example.test");
        jdbc.update("update conversations set contact_id = ? where id = ?::uuid", contact, id);
        return id;
    }

    private void send(String token, String text) {
        http.post().uri("/conversation/messages").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"clientMessageId\":\"" + UUID.randomUUID() + "\",\"body\":\"" + text + "\"}")
            .retrieve().toBodilessEntity();
    }

    private StompSession connect(String token) throws Exception {
        return connect(token, new LinkedBlockingQueue<>());
    }

    private StompSession connect(String token, BlockingQueue<String> errors) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        return stomp.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers,
            new StompSessionHandlerAdapter() {
                @Override
                public void handleFrame(StompHeaders frameHeaders, Object payload) {
                    errors.add(String.valueOf(frameHeaders.get("message")));
                }

                @Override
                public void handleTransportError(StompSession session, Throwable exception) {
                    errors.add(exception.toString());
                }
            }).get(5, TimeUnit.SECONDS);
    }

    private static BlockingQueue<Object> subscribe(StompSession session, String conversationId) throws Exception {
        return subscribeTo(session, "/topic/conversations/" + conversationId);
    }

    private static BlockingQueue<Object> subscribeTo(StompSession session, String destination) throws Exception {
        BlockingQueue<Object> events = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                events.add(payload);
            }
        });
        // Give the server a moment to register the subscription before events are sent.
        Thread.sleep(200);
        return events;
    }
}
