package io.github.mesubash.quokra.widget;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.mesubash.quokra.TestcontainersConfiguration;
import io.github.mesubash.quokra.session.SessionTokens;
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

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class WidgetConversationControllerTests {

    static final UUID TENANT_ID = UUID.randomUUID();
    static final String BASE = "/api/v1/widget/conversation";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionTokens sessionTokens;

    String token;

    @BeforeEach
    void setUp() {
        jdbc.update("""
            insert into tenants (id, slug, display_name, publishable_key, bot_config)
            values (?, 'acme', 'Acme Inc', 'pk_test_acme', 'test')
            """, TENANT_ID);
        token = sessionTokens.issue(TENANT_ID).token();
    }

    @Test
    void requiresASessionToken() {
        MvcTestResult result = mvc.get().uri(BASE).exchange();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result).bodyJson().extractingPath("$.error.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void rejectsAnInvalidToken() {
        assertThat(mvc.get().uri(BASE).header(HttpHeaders.AUTHORIZATION, "Bearer nope"))
            .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void noConversationBeforeStarting() {
        assertThat(get(BASE)).hasStatusOk().bodyJson().extractingPath("$.conversation").isNull();
    }

    @Test
    void startCreatesThenResumes() {
        MvcTestResult first = post(BASE, null);
        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(first).bodyJson().extractingPath("$.conversation.status").isEqualTo("BOT");
        assertThat(first).bodyJson().extractingPath("$.messages[0].senderType").isEqualTo("BOT");

        MvcTestResult again = post(BASE, null);
        assertThat(again).hasStatusOk();
        assertThat(id(again, "$.conversation.id")).isEqualTo(id(first, "$.conversation.id"));
    }

    @Test
    void sendsAMessage() {
        post(BASE, null);

        MvcTestResult result = post(BASE + "/messages", "{\"clientMessageId\":\"c-1\",\"body\":\"Hello\"}");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.message.senderType").isEqualTo("CUSTOMER");
        assertThat(result).bodyJson().extractingPath("$.message.body").isEqualTo("Hello");
        assertThat(result).bodyJson().extractingPath("$.message.clientMessageId").isEqualTo("c-1");
    }

    @Test
    void retriedSendReturnsTheSameMessage() {
        post(BASE, null);
        String body = "{\"clientMessageId\":\"c-1\",\"body\":\"Hello\"}";

        assertThat(id(post(BASE + "/messages", body), "$.message.id"))
            .isEqualTo(id(post(BASE + "/messages", body), "$.message.id"));
    }

    @Test
    void sendingWithoutAConversationIsAConflict() {
        MvcTestResult result = post(BASE + "/messages", "{\"body\":\"Hello\"}");

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.error.code").isEqualTo("NO_OPEN_CONVERSATION");
    }

    @Test
    void emptyAndTooLongMessagesAreRejected() {
        post(BASE, null);

        assertThat(post(BASE + "/messages", "{\"body\":\"\"}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(post(BASE + "/messages", "{\"body\":\"" + "x".repeat(2001) + "\"}"))
            .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
            .bodyJson().extractingPath("$.error.code").isEqualTo("MESSAGE_TOO_LONG");
    }

    @Test
    void listsMessagesAfterACursor() {
        post(BASE, null);
        String firstId = id(post(BASE + "/messages", "{\"clientMessageId\":\"c-1\",\"body\":\"price\"}"), "$.message.id");
        post(BASE + "/messages", "{\"clientMessageId\":\"c-2\",\"body\":\"password\"}");

        MvcTestResult page = get(BASE + "/messages?after=" + firstId);

        assertThat(page).hasStatusOk();
        assertThat(page).bodyJson().extractingPath("$.items[*].body")
            .isEqualTo(List.of("Pricing answer.", "password", "Account answer."));
        assertThat(page).bodyJson().extractingPath("$.nextCursor").isNull();
    }

    @Test
    void handoffMovesToWaitingAndHidesInternalFields() {
        post(BASE, null);

        MvcTestResult result = post(BASE + "/handoff", null);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.conversation.status").isEqualTo("WAITING");
        assertThat(result).bodyJson().doesNotHavePath("$.conversation.priority");
        assertThat(result).bodyJson().doesNotHavePath("$.conversation.handoffReason");
        assertThat(post(BASE + "/handoff", null)).hasStatusOk();
    }

    @Test
    void anotherVisitorCannotSeeTheConversation() {
        post(BASE, null);
        String otherToken = sessionTokens.issue(TENANT_ID).token();

        assertThat(mvc.get().uri(BASE).header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
            .hasStatusOk()
            .bodyJson().extractingPath("$.conversation").isNull();
    }

    private MvcTestResult get(String uri) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private MvcTestResult post(String uri, String json) {
        var request = mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (json != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return request.exchange();
    }

    private static String id(MvcTestResult result, String path) {
        try {
            return JsonPath.read(result.getResponse().getContentAsString(), path);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
