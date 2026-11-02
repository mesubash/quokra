package io.github.mesubash.quokra.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class PersonNamesTests {

    @Test
    void emailsBecomeNames() {
        assertThat(PersonNames.fromEmail("sam.lee99@example.test")).isEqualTo("Sam Lee");
        assertThat(PersonNames.fromEmail("SAM_lee@x.test")).isEqualTo("Sam Lee");
        assertThat(PersonNames.fromEmail("ops+chat@x.test")).isEqualTo("Ops Chat");
        assertThat(PersonNames.safe("1234@x.test")).isEqualTo(PersonNames.FALLBACK);
        assertThat(PersonNames.safe("  Sam Lee ")).isEqualTo("Sam Lee");
        assertThat(PersonNames.first("sam.lee@example.test")).isEqualTo("Sam");
        assertThat(PersonNames.first(null)).isEqualTo(PersonNames.FALLBACK);
    }

    @Test
    void anEmailInTheTokenNeverBecomesTheAgentsName() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256")
            .subject("agent-1").claim("preferred_username", "sam.lee@example.test").build();

        assertThat(new AgentJwtConverter("roles", "SUPPORT_AGENT", "QUOKRA_ADMIN", "").displayName(jwt)).isEqualTo("Sam Lee");
    }
}
