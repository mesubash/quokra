package io.github.mesubash.quokra.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mesubash.quokra.TestcontainersConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class SpaControllerTests {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void addTenant() {
        jdbc.update("""
            insert into tenants (id, slug, display_name, publishable_key, widget_origins, console_origins, bot_config)
            values (?, 'acme', 'Acme Inc', 'pk_test_acme', ?, ?, 'test')
            """, UUID.randomUUID(),
            new String[] {"https://www.acme.test", "http://localhost:5500", "https://evil.test; script-src *"},
            new String[] {"https://admin.acme.test"});
    }

    @Test
    void chatPanelLoadsTheUi() {
        assertThat(mvc.get().uri("/w/acme")).hasStatusOk().hasForwardedUrl("/index.html");
    }

    @Test
    void chatPanelCanOnlyBeEmbeddedByTheTenantsSites() {
        assertThat(mvc.get().uri("/w/acme")).hasHeader("Content-Security-Policy",
            "frame-ancestors 'self' https://www.acme.test http://localhost:5500");
    }

    @Test
    void unknownTenantCanOnlyBeFramedByItself() {
        assertThat(mvc.get().uri("/w/nope")).hasHeader("Content-Security-Policy", "frame-ancestors 'self'");
    }

    @Test
    void consoleLoadsTheUi() {
        assertThat(mvc.get().uri("/console")).hasStatusOk().hasForwardedUrl("/index.html");
    }

    @Test
    void consoleCanOnlyBeEmbeddedByTheTenantsDashboards() {
        assertThat(mvc.get().uri("/console?tenant=acme")).hasHeader("Content-Security-Policy",
            "frame-ancestors 'self' https://admin.acme.test");
    }

    @Test
    void consoleWithoutTenantCanOnlyBeFramedByItself() {
        assertThat(mvc.get().uri("/console")).hasHeader("Content-Security-Policy", "frame-ancestors 'self'");
    }

    @Test
    void onlyPlainOriginsAreUsed() {
        assertThat(SpaController.frameAncestors(List.of("https://ok.test:8443", "javascript:alert(1)", "https://a.test/path")))
            .isEqualTo("frame-ancestors 'self' https://ok.test:8443");
    }
}
