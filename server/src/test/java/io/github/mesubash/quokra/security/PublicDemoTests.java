package io.github.mesubash.quokra.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mesubash.quokra.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

// The public demo (profiles demo + public-demo): the playground's site may use the demo sign-in, and the
// demo company allows it.
@SpringBootTest(properties = {"QUOKRA_DEMO_ORIGINS=https://play.example.test/", "TENANT_SECRET_ENCRYPTION_KEY=ZGV2LW9ubHktdGVuYW50LXNlY3JldC1rZXktMzJieXQ=",
    "SMTP_HOST=smtp.example.test", "QUOKRA_MAIL_FROM=demo@example.test"})
@ActiveProfiles({"demo", "public-demo"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PublicDemoTests {

    static final String PLAYGROUND = "https://play.example.test";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    io.github.mesubash.quokra.mail.MailCheck mail;

    @Test
    void thePublicDemoNeverSendsEmailEvenWithSmtpSet() {
        assertThat(mail.configured()).isFalse();
    }

    @Test
    void thePlaygroundSiteIsAllowed() {
        List<String> origins = jdbc.queryForList(
            "select unnest(widget_origins || console_origins) from tenants where slug = 'demo'", String.class);
        assertThat(origins).contains(PLAYGROUND);
    }

    @Test
    void thePlaygroundCanUseTheDemoSignInButOtherSitesCant() {
        var allowed = mvc.post().uri("/dev/agent-token").header(HttpHeaders.ORIGIN, PLAYGROUND)
            .contentType(MediaType.APPLICATION_JSON).content("{\"sub\":\"0b9e8a3c-0001-4000-8000-00000000a001\",\"name\":\"Sam Lee\"}")
            .exchange();
        assertThat(allowed).hasStatusOk();
        assertThat(allowed.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(PLAYGROUND);

        assertThat(mvc.post().uri("/dev/agent-token").header(HttpHeaders.ORIGIN, "https://elsewhere.example.test")
            .contentType(MediaType.APPLICATION_JSON).content("{\"sub\":\"x\"}").exchange())
            .hasStatus(HttpStatus.FORBIDDEN);
    }
}
