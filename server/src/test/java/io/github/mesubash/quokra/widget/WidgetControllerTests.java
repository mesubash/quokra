package io.github.mesubash.quokra.widget;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.mesubash.quokra.TestcontainersConfiguration;
import io.github.mesubash.quokra.session.SessionTokens;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
class WidgetControllerTests {

    static final UUID TENANT_ID = UUID.randomUUID();

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionTokens sessionTokens;

    @BeforeEach
    void addTenant() {
        jdbc.update("""
            insert into tenants (id, slug, display_name, publishable_key, primary_color, logo_url, bot_config)
            values (?, 'acme', 'Acme Inc', 'pk_test_acme', '#123456', 'https://acme.test/logo.png', 'acme')
            """, TENANT_ID);
    }

    @Test
    void returnsPublicTenantConfig() {
        MvcTestResult result = mvc.get().uri("/api/v1/widget/tenants/pk_test_acme").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.slug").isEqualTo("acme");
        assertThat(result).bodyJson().extractingPath("$.displayName").isEqualTo("Acme Inc");
        assertThat(result).bodyJson().extractingPath("$.branding.primaryColor").isEqualTo("#123456");
        assertThat(result).bodyJson().extractingPath("$.branding.logoUrl").isEqualTo("https://acme.test/logo.png");
    }

    @Test
    void unknownTenantIsNotFound() {
        MvcTestResult result = mvc.get().uri("/api/v1/widget/tenants/pk_test_nope").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.error.code").isEqualTo("TENANT_NOT_FOUND");
    }

    @Test
    void startsGuestSession() throws Exception {
        MvcTestResult result = mvc.post().uri("/api/v1/widget/sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"publishableKey\":\"pk_test_acme\"}")
            .exchange();

        assertThat(result).hasStatus(HttpStatus.CREATED);
        String body = result.getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.sessionToken");
        String visitorId = JsonPath.read(body, "$.visitorId");

        SessionTokens.Session session = sessionTokens.verify(token).orElseThrow();
        assertThat(session.tenantId()).isEqualTo(TENANT_ID);
        assertThat(session.visitorId()).hasToString(visitorId);
    }

    @Test
    void sessionForUnknownTenantIsNotFound() {
        assertThat(mvc.post().uri("/api/v1/widget/sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"publishableKey\":\"pk_test_nope\"}"))
            .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void sessionWithoutKeyIsInvalid() {
        MvcTestResult result = mvc.post().uri("/api/v1/widget/sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}")
            .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.error.code").isEqualTo("VALIDATION_ERROR");
        assertThat(result).bodyJson().extractingPath("$.error.details.publishableKey").isNotNull();
    }
}
