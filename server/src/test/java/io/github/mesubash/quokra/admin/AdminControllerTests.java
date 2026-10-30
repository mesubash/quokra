package io.github.mesubash.quokra.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.mesubash.quokra.TestcontainersConfiguration;
import io.github.mesubash.quokra.security.DevAgentTokens;
import java.util.List;
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

@SpringBootTest(properties = {
    "quokra.dev.agent-tokens=true",
    "quokra.tenant-secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class AdminControllerTests {

    static final String BASE = "/api/v1/admin";
    static final String ACME = """
        {"slug":"acme","displayName":"Acme","widgetOrigins":["https://www.acme.test/","https://shop.acme.test"],
         "consoleOrigins":["https://admin.acme.test"],"primaryColor":"#0051fb","botConfig":"test"}
        """;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DevAgentTokens devTokens;

    String admin;
    String agent;

    @BeforeEach
    void tokens() throws Exception {
        admin = devTokens.sign("root", "Root", List.of("QUOKRA_ADMIN"));
        agent = devTokens.sign("alice", "Alice", List.of("SUPPORT_AGENT"));
    }

    MvcTestResult call(String method, String token, String uri, String json) {
        var request = switch (method) {
            case "POST" -> mvc.post();
            case "PUT" -> mvc.put();
            case "DELETE" -> mvc.delete();
            default -> mvc.get();
        };
        var r = request.uri(BASE + uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (json != null) {
            r = r.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return r.exchange();
    }

    @Test
    void onlyAdminsGetIn() {
        assertThat(call("GET", agent, "/tenants", null)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri(BASE + "/tenants")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(call("GET", admin, "/tenants", null)).hasStatusOk();
    }

    @Test
    void createsACompanyWithAPublishableKey() throws Exception {
        MvcTestResult created = call("POST", admin, "/tenants", ACME);

        assertThat(created).hasStatus(HttpStatus.CREATED);
        assertThat(created).bodyJson().extractingPath("$.publishableKey").asString().startsWith("pk_live_");
        assertThat(created).bodyJson().extractingPath("$.widgetOrigins")
            .isEqualTo(List.of("https://www.acme.test", "https://shop.acme.test"));
        assertThat(created).bodyJson().extractingPath("$.hasSecret").isEqualTo(false);
        // Its chat works straight away.
        String pk = JsonPath.read(created.getResponse().getContentAsString(), "$.publishableKey");
        assertThat(mvc.get().uri("/api/v1/widget/tenants/" + pk)).bodyJson()
            .extractingPath("$.displayName").isEqualTo("Acme");
    }

    @Test
    void rejectsBadInput() {
        assertThat(call("POST", admin, "/tenants", ACME.replace("\"acme\"", "\"Not A Slug!\""))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(call("POST", admin, "/tenants", ACME.replace("https://admin.acme.test", "https://admin.acme.test/path")))
            .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(call("POST", admin, "/tenants", ACME.replace("\"test\"", "\"missing-bot\""))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(call("POST", admin, "/tenants", ACME.replace("#0051fb", "blue"))).hasStatus(HttpStatus.BAD_REQUEST);

        call("POST", admin, "/tenants", ACME);
        assertThat(call("POST", admin, "/tenants", ACME)).hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.error.code").isEqualTo("SLUG_TAKEN");
    }

    @Test
    void editsAndKeepsTheSlug() {
        call("POST", admin, "/tenants", ACME);

        MvcTestResult updated = call("PUT", admin, "/tenants/acme",
            ACME.replace("\"Acme\"", "\"Acme Shop\"").replace("\"acme\"", "\"ignored\""));

        assertThat(updated).bodyJson().extractingPath("$.displayName").isEqualTo("Acme Shop");
        assertThat(updated).bodyJson().extractingPath("$.slug").isEqualTo("acme");
    }

    @Test
    void savedRepliesCanBeAddedEditedAndRemoved() throws Exception {
        call("POST", admin, "/tenants", ACME);

        MvcTestResult added = call("POST", admin, "/tenants/acme/replies", """
            {"title":" Hours ","body":"Hi {name}, we're open 9-5."}""");
        assertThat(added).hasStatus(HttpStatus.CREATED);
        assertThat(added).bodyJson().extractingPath("$.title").isEqualTo("Hours");
        String id = JsonPath.read(added.getResponse().getContentAsString(), "$.id");

        assertThat(call("PUT", admin, "/tenants/acme/replies/" + id, """
            {"title":"Opening hours","body":"9-5"}""")).hasStatusOk();
        assertThat(call("GET", admin, "/tenants/acme/replies", null)).bodyJson()
            .extractingPath("$.items[*].title").isEqualTo(List.of("Opening hours"));

        assertThat(call("POST", admin, "/tenants/acme/replies", """
            {"title":"","body":"x"}""")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(call("DELETE", admin, "/tenants/acme/replies/" + id, null)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(call("DELETE", admin, "/tenants/acme/replies/" + id, null)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void secretKeyIsShownOnce() {
        call("POST", admin, "/tenants", ACME);

        MvcTestResult secret = call("POST", admin, "/tenants/acme/secret", null);

        assertThat(secret).bodyJson().extractingPath("$.secretKey").asString().startsWith("sk_");
        assertThat(call("GET", admin, "/tenants/acme", null)).bodyJson()
            .extractingPath("$.hasSecret").isEqualTo(true);
        assertThat(call("GET", admin, "/tenants/acme", null)).bodyJson().doesNotHavePath("$.secretKey");
    }

    @Test
    void invitedAgentJoinsOnFirstSignInWithThatVerifiedEmail() throws Exception {
        call("POST", admin, "/tenants", ACME);

        assertThat(call("POST", admin, "/tenants/acme/agents", "{\"email\":\"Sam@Acme.test\"}")).bodyJson()
            .extractingPath("$.result").isEqualTo("INVITED");

        // Same email, but not verified: no.
        String unverified = devTokens.sign("sam-sub", "Sam", List.of("SUPPORT_AGENT"));
        mvc.get().uri("/api/v1/agent/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + unverified).exchange();
        assertThat(call("GET", admin, "/tenants/acme/agents", null)).bodyJson()
            .extractingPath("$.agents").asArray().isEmpty();

        String verified = devTokens.sign("sam-sub", "Sam", List.of("SUPPORT_AGENT"), "sam@acme.test");
        MvcTestResult me = mvc.get().uri("/api/v1/agent/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + verified)
            .exchange();

        assertThat(me).bodyJson().extractingPath("$.tenants").isEqualTo(List.of("acme"));
        MvcTestResult list = call("GET", admin, "/tenants/acme/agents", null);
        assertThat(list).bodyJson().extractingPath("$.agents[0].sub").isEqualTo("sam-sub");
        assertThat(list).bodyJson().extractingPath("$.invites").asArray().isEmpty();
    }

    @Test
    void knownAgentIsAddedStraightAway() throws Exception {
        call("POST", admin, "/tenants", ACME);
        call("POST", admin, "/tenants", ACME.replace("\"acme\"", "\"globex\""));
        call("POST", admin, "/tenants/acme/agents", "{\"email\":\"sam@acme.test\"}");
        String sam = devTokens.sign("sam-sub", "Sam", List.of("SUPPORT_AGENT"), "sam@acme.test");
        mvc.get().uri("/api/v1/agent/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + sam).exchange();

        assertThat(call("POST", admin, "/tenants/globex/agents", "{\"email\":\"SAM@acme.test\"}")).bodyJson()
            .extractingPath("$.result").isEqualTo("ADDED");
        assertThat(call("DELETE", admin, "/tenants/globex/agents/sam-sub", null)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(call("DELETE", admin, "/tenants/globex/agents/sam-sub", null)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void meSaysWhichPartsSomeoneMayUse() throws Exception {
        String both = devTokens.sign("root", "Root", List.of("SUPPORT_AGENT", "QUOKRA_ADMIN"));
        MvcTestResult adminOnly = mvc.get().uri("/api/v1/agent/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).exchange();
        MvcTestResult agentAndAdmin = mvc.get().uri("/api/v1/agent/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + both).exchange();

        assertThat(adminOnly).bodyJson().extractingPath("$.agent").isEqualTo(false);
        assertThat(adminOnly).bodyJson().extractingPath("$.admin").isEqualTo(true);
        assertThat(agentAndAdmin).bodyJson().extractingPath("$.agent").isEqualTo(true);
    }
}
