package io.github.mesubash.quokra.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mesubash.quokra.TestcontainersConfiguration;
import io.github.mesubash.quokra.security.DevAgentTokens;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

// Company admins manage their own company only: by the Admin switch on their membership, or by the token.
@SpringBootTest(properties = {
    "quokra.dev.agent-tokens=true",
    "quokra.admins.company-claim=permissions",
    "quokra.admins.company-value=quokra:{tenant}:admin"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class CompanyAdminTests {

    static final String BASE = "/api/v1/admin";
    static final String ACME = """
        {"slug":"acme","displayName":"Acme","widgetOrigins":["https://www.acme.test"],
         "consoleOrigins":["https://admin.acme.test"],"botConfig":"test"}
        """;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DevAgentTokens devTokens;

    String root;

    @BeforeEach
    void companies() throws Exception {
        root = devTokens.sign("root", "Root", List.of("QUOKRA_ADMIN"));
        call("POST", root, "/tenants", ACME);
        call("POST", root, "/tenants", ACME.replace("acme", "globex").replace("Acme", "Globex"));
    }

    MvcTestResult call(String method, String token, String uri, String json) {
        var request = switch (method) {
            case "POST" -> mvc.post();
            case "PUT" -> mvc.put();
            case "DELETE" -> mvc.delete();
            default -> mvc.get();
        };
        var r = request.uri(uri.startsWith("/api") ? uri : BASE + uri)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (json != null) {
            r = r.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return r.exchange();
    }

    // Signs in with a verified email, which turns invites into memberships.
    String signIn(String sub, String email) throws Exception {
        String token = devTokens.sign(sub, sub, List.of("SUPPORT_AGENT"), email);
        call("GET", token, "/api/v1/agent/me", null);
        return token;
    }

    @Test
    void invitedAsAdminManagesOnlyThatCompany() throws Exception {
        call("POST", root, "/tenants/acme/agents", "{\"email\":\"pat@acme.test\",\"admin\":true}");
        String pat = signIn("pat", "pat@acme.test");

        assertThat(call("GET", pat, "/api/v1/agent/me", null)).bodyJson()
            .extractingPath("$.manages").isEqualTo(List.of("acme"));
        assertThat(call("GET", pat, "/tenants", null)).bodyJson()
            .extractingPath("$.items[*].slug").isEqualTo(List.of("acme"));
        assertThat(call("GET", pat, "/tenants/acme", null)).hasStatusOk();
        assertThat(call("PUT", pat, "/tenants/acme", ACME.replace("\"Acme\"", "\"Acme Two\""))).hasStatusOk();
        assertThat(call("GET", pat, "/tenants/acme/agents", null)).hasStatusOk();
        assertThat(call("GET", pat, "/bots", null)).hasStatusOk();

        assertThat(call("GET", pat, "/tenants/globex", null)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(call("PUT", pat, "/tenants/globex/bot", "{}")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(call("POST", pat, "/tenants", ACME.replace("acme", "initech"))).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void theAdminSwitchTurnsItOnAndOff() throws Exception {
        call("POST", root, "/tenants/acme/agents", "{\"email\":\"sam@acme.test\"}");
        String sam = signIn("sam", "sam@acme.test");
        assertThat(call("GET", sam, "/tenants/acme", null)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(call("GET", sam, "/bots", null)).hasStatus(HttpStatus.FORBIDDEN);

        // Another company admin can make Sam one too.
        call("POST", root, "/tenants/acme/agents", "{\"email\":\"pat@acme.test\",\"admin\":true}");
        String pat = signIn("pat", "pat@acme.test");
        assertThat(call("PUT", pat, "/tenants/acme/agents/sam", "{\"admin\":true}")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(call("GET", sam, "/tenants/acme", null)).hasStatusOk();
        assertThat(call("GET", root, "/tenants/acme/agents", null)).bodyJson()
            .extractingPath("$.agents[?(@.sub == 'sam')].admin").isEqualTo(List.of(true));

        assertThat(call("PUT", pat, "/tenants/acme/agents/sam", "{\"admin\":false}")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(call("GET", sam, "/tenants/acme", null)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(call("PUT", pat, "/tenants/acme/agents/nobody", "{\"admin\":true}")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void theTokenCanNameTheCompaniesSomeoneManages() throws Exception {
        // No roles and no membership: only the permissions claim.
        String lee = devTokens.sign("lee", "Lee", List.of(), null,
            Map.of("permissions", List.of("quokra:globex:admin", "quokra:missing:admin", "billing:read")));

        assertThat(call("GET", lee, "/api/v1/agent/me", null)).bodyJson()
            .extractingPath("$.manages").isEqualTo(List.of("globex"));
        assertThat(call("GET", lee, "/tenants/globex", null)).hasStatusOk();
        assertThat(call("GET", lee, "/tenants/acme", null)).hasStatus(HttpStatus.FORBIDDEN);
        // Still not an agent.
        assertThat(call("GET", lee, "/api/v1/agent/tenants", null)).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void serverAdminsStillManageEverything() {
        assertThat(call("GET", root, "/tenants", null)).bodyJson()
            .extractingPath("$.items[*].slug").asArray().contains("acme", "globex");
        assertThat(call("GET", root, "/tenants/globex", null)).hasStatusOk();
    }
}
