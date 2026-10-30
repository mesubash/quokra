package io.github.mesubash.quokra.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mesubash.quokra.TestcontainersConfiguration;
import io.github.mesubash.quokra.session.SessionTokens;
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

// Rate limits, the error format and CORS (ARCHITECTURE.md §13).
@SpringBootTest(properties = "quokra.dev.agent-tokens=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class HardeningTests {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionTokens sessionTokens;

    UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void addTenant() {
        jdbc.update("""
            insert into tenants (id, slug, display_name, publishable_key, bot_config)
            values (?, 'acme', 'Acme Inc', 'pk_test_acme', 'test')
            """, tenantId);
    }

    MvcTestResult newSession(String ip) {
        return mvc.post().uri("/api/v1/widget/sessions").contentType(MediaType.APPLICATION_JSON)
            .content("{\"publishableKey\":\"pk_test_acme\"}")
            .with(request -> {
                request.setRemoteAddr(ip);
                return request;
            })
            .exchange();
    }

    @Test
    void newSessionsAreLimitedPerAddress() {
        String ip = "10.0.0." + (int) (Math.random() * 250 + 1);
        for (int i = 0; i < RateLimiter.SESSIONS_PER_MINUTE; i++) {
            assertThat(newSession(ip)).hasStatus(HttpStatus.CREATED);
        }

        MvcTestResult refused = newSession(ip);

        assertThat(refused).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refused).containsHeader(HttpHeaders.RETRY_AFTER);
        assertThat(refused).bodyJson().extractingPath("$.error.code").isEqualTo("RATE_LIMITED");
        assertThat(newSession("192.168.77.1")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void messagesAreLimitedPerSession() {
        String token = sessionTokens.issue(tenantId).token();
        mvc.post().uri("/api/v1/widget/conversation").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
        MvcTestResult last = null;
        for (int i = 0; i <= RateLimiter.MESSAGES_PER_MINUTE; i++) {
            last = mvc.post().uri("/api/v1/widget/conversation/messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"hello " + i + "\"}").exchange();
        }

        assertThat(last).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    }

    String agentToken() throws Exception {
        String agent = mvc.post().uri("/dev/agent-token").contentType(MediaType.APPLICATION_JSON)
            .content("{\"sub\":\"alice\"}").exchange().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(agent, "$.accessToken");
    }

    @Test
    void badParametersUseTheErrorFormat() throws Exception {
        String token = agentToken();

        MvcTestResult badStatus = mvc.get().uri("/api/v1/agent/conversations?status=NOPE")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
        MvcTestResult missing = mvc.get().uri("/api/v1/agent/conversations")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
        MvcTestResult badId = mvc.get().uri("/api/v1/agent/conversations/not-a-uuid")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();

        for (MvcTestResult result : new MvcTestResult[] {badStatus, missing, badId}) {
            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.error.code").isEqualTo("VALIDATION_ERROR");
        }
    }

    @Test
    void wrongMethodUsesTheErrorFormat() throws Exception {
        MvcTestResult result = mvc.delete().uri("/api/v1/agent/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + agentToken()).exchange();

        assertThat(result).hasStatus(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(result).bodyJson().extractingPath("$.error.code").isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void otherSitesCantCallTheWidgetApi() {
        assertThat(mvc.options().uri("/api/v1/widget/sessions")
                .header(HttpHeaders.ORIGIN, "https://evil.test")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
            .doesNotContainHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }
}
