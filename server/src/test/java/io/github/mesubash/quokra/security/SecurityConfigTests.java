package io.github.mesubash.quokra.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mesubash.quokra.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityConfigTests {

    @Autowired
    MockMvcTester mvc;

    @Test
    void healthIsPublicAndUp() {
        assertThat(mvc.get().uri("/actuator/health"))
            .hasStatusOk()
            .bodyJson().extractingPath("$.status").isEqualTo("UP");
    }

    @Test
    void unknownPathsAreBlocked() {
        assertThat(mvc.get().uri("/api/v1/anything"))
            .hasStatus(HttpStatus.UNAUTHORIZED)
            .bodyJson().extractingPath("$.error.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void pagesCanBeFramed() {
        assertThat(mvc.get().uri("/actuator/health")).doesNotContainHeader("X-Frame-Options");
    }
}
