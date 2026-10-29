package io.github.mesubash.quokra.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mesubash.quokra.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SpaControllerTests {

    @Autowired
    MockMvcTester mvc;

    @Test
    void chatPanelLoadsTheUi() {
        assertThat(mvc.get().uri("/w/demo")).hasStatusOk().hasForwardedUrl("/index.html");
    }

    @Test
    void consoleLoadsTheUi() {
        assertThat(mvc.get().uri("/console")).hasStatusOk().hasForwardedUrl("/index.html");
    }
}
