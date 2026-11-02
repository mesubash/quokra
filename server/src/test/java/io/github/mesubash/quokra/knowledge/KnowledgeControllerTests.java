package io.github.mesubash.quokra.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.mesubash.quokra.TestcontainersConfiguration;
import io.github.mesubash.quokra.security.DevAgentTokens;
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
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "quokra.dev.agent-tokens=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class KnowledgeControllerTests {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String ACME = """
        {"slug":"acme","displayName":"Acme","widgetOrigins":["https://www.acme.test"],
         "consoleOrigins":["https://admin.acme.test"],"botConfig":"test"}
        """;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DevAgentTokens devTokens;

    @Autowired
    JdbcTemplate jdbc;

    String root;

    @BeforeEach
    void companies() throws Exception {
        root = devTokens.sign("root", "Root", List.of("QUOKRA_ADMIN"));
        call("POST", root, "/api/v1/admin/tenants", ACME);
        call("POST", root, "/api/v1/admin/tenants", ACME.replace("acme", "globex").replace("Acme", "Globex"));
    }

    MvcTestResult call(String method, String token, String uri, String json) {
        var request = switch (method) {
            case "POST" -> mvc.post();
            case "PUT" -> mvc.put();
            case "DELETE" -> mvc.delete();
            default -> mvc.get();
        };
        var r = request.uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (json != null) {
            r = r.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return r.exchange();
    }

    static String doc(String title, String body) {
        return JSON.writeValueAsString(java.util.Map.of("title", title, "body", body));
    }

    String add(String slug, String title, String body) throws Exception {
        MvcTestResult added = call("POST", root, "/api/v1/admin/tenants/" + slug + "/knowledge", doc(title, body));
        assertThat(added).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(added.getResponse().getContentAsString(), "$.id");
    }

    int chunks(String id) {
        return jdbc.queryForObject("select count(*) from knowledge_chunks where document_id = ?", Integer.class,
            UUID.fromString(id));
    }

    @Test
    void addsListsEditsAndRemovesADocument() throws Exception {
        String id = add("acme", " Shipping ", "# Shipping\nTwo days.\n\n# Returns\n30 days.");
        assertThat(chunks(id)).isEqualTo(2);

        MvcTestResult list = call("GET", root, "/api/v1/admin/tenants/acme/knowledge", null);
        assertThat(list).bodyJson().extractingPath("$.items[0].title").isEqualTo("Shipping");
        assertThat(list).bodyJson().extractingPath("$.items[0].chunks").isEqualTo(2);
        assertThat(list).bodyJson().extractingPath("$.items[0].updatedBy").isEqualTo("root");

        // Editing splits it again.
        assertThat(call("PUT", root, "/api/v1/admin/tenants/acme/knowledge/" + id, doc("Shipping", "Only one part.")))
            .hasStatusOk().bodyJson().extractingPath("$.body").isEqualTo("Only one part.");
        assertThat(chunks(id)).isEqualTo(1);

        assertThat(call("DELETE", root, "/api/v1/admin/tenants/acme/knowledge/" + id, null)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(chunks(id)).isZero();
        assertThat(call("GET", root, "/api/v1/admin/tenants/acme/knowledge/" + id, null)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void documentsBelongToOneCompany() throws Exception {
        String id = add("acme", "Prices", "Ten euros.");

        // Not reachable through another company's slug.
        assertThat(call("GET", root, "/api/v1/admin/tenants/globex/knowledge/" + id, null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(call("DELETE", root, "/api/v1/admin/tenants/globex/knowledge/" + id, null)).hasStatus(HttpStatus.NOT_FOUND);

        // Company admins of another company and plain agents don't get in.
        String other = devTokens.sign("lee", "Lee", List.of("SUPPORT_AGENT"));
        jdbc.update("insert into tenant_agents (tenant_id, agent_sub, admin) select id, 'lee', true from tenants where slug = 'globex'");
        assertThat(call("GET", other, "/api/v1/admin/tenants/acme/knowledge", null)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(call("GET", other, "/api/v1/admin/tenants/globex/knowledge", null)).hasStatusOk()
            .bodyJson().extractingPath("$.items").asArray().isEmpty();
    }

    @Test
    void rejectsEmptyAndTooBig() {
        String base = "/api/v1/admin/tenants/acme/knowledge";
        assertThat(call("POST", root, base, doc("", "Body"))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(call("POST", root, base, doc("Title", " "))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(call("POST", root, base, doc("t".repeat(KnowledgeDocuments.MAX_TITLE + 1), "Body")))
            .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(call("POST", root, base, doc("Title", "x".repeat(KnowledgeDocuments.MAX_BODY + 1))))
            .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aCompanyHasAtMostSoManyDocuments() throws Exception {
        jdbc.update("""
            insert into knowledge_documents (id, tenant_id, title, body)
            select gen_random_uuid(), t.id, 'Doc ' || n, 'Body' from tenants t, generate_series(1, ?) n where t.slug = 'acme'
            """, KnowledgeDocuments.MAX_DOCUMENTS);

        assertThat(call("POST", root, "/api/v1/admin/tenants/acme/knowledge", doc("One more", "Body")))
            .hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.error.code").isEqualTo("TOO_MANY_DOCUMENTS");
    }
}
