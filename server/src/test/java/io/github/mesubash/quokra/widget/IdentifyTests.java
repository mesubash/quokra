package io.github.mesubash.quokra.widget;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.mesubash.quokra.TestcontainersConfiguration;
import io.github.mesubash.quokra.session.SessionTokens;
import io.github.mesubash.quokra.tenant.TenantSecrets;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
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

@SpringBootTest(properties = "quokra.tenant-secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class IdentifyTests {

    static final String BASE = "/api/v1/widget";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionTokens sessionTokens;

    @Autowired
    TenantSecrets secrets;

    UUID tenantId = UUID.randomUUID();
    String secret;

    @BeforeEach
    void setUp() {
        jdbc.update("""
            insert into tenants (id, slug, display_name, publishable_key, bot_config)
            values (?, 'acme', 'Acme Inc', 'pk_test_acme', 'test'),
                   (?, 'globex', 'Globex', 'pk_test_globex', 'test')
            """, tenantId, UUID.randomUUID());
        secret = secrets.rotate("acme");
        secrets.rotate("globex");
    }

    String identityToken(String secretKey, String sub) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(sub).claim("name", "Pat Doe")
            .claim("email", "pat@example.test").issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(Duration.ofMinutes(5)))).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secretKey.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    MvcTestResult post(String token, String uri, String json) {
        var request = mvc.post().uri(BASE + uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (json != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return request.exchange();
    }

    String identify(String session, String identityToken) throws Exception {
        MvcTestResult result = post(session, "/sessions/identify", "{\"identityToken\":\"" + identityToken + "\"}");
        assertThat(result).hasStatusOk();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.sessionToken");
    }

    String conversationId(String session) throws Exception {
        return JsonPath.read(post(session, "/conversation", null).getResponse().getContentAsString(), "$.conversation.id");
    }

    String guest() {
        return sessionTokens.issue(tenantId).token();
    }

    @Test
    void identifyReturnsASessionForTheCustomer() throws Exception {
        MvcTestResult result = post(guest(), "/sessions/identify",
            "{\"identityToken\":\"" + identityToken(secret, "user-1") + "\"}");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.customer.sub").isEqualTo("user-1");
        String session = JsonPath.read(result.getResponse().getContentAsString(), "$.sessionToken");
        assertThat(sessionTokens.verify(session).orElseThrow().customerSub()).isEqualTo("user-1");
    }

    @Test
    void tokenFromAnotherTenantIsRejected() throws Exception {
        String globexSecret = secrets.rotate("globex");

        MvcTestResult result = post(guest(), "/sessions/identify",
            "{\"identityToken\":\"" + identityToken(globexSecret, "user-1") + "\"}");

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result).bodyJson().extractingPath("$.error.code").isEqualTo("IDENTITY_TOKEN_INVALID");
    }

    @Test
    void identifyNeedsASession() throws Exception {
        assertThat(mvc.post().uri(BASE + "/sessions/identify").contentType(MediaType.APPLICATION_JSON)
            .content("{\"identityToken\":\"" + identityToken(secret, "user-1") + "\"}"))
            .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void guestConversationBecomesTheCustomers() throws Exception {
        String session = guest();
        String guestConversation = conversationId(session);

        String customerSession = identify(session, identityToken(secret, "user-1"));

        assertThat(conversationId(customerSession)).isEqualTo(guestConversation);
        assertThat(jdbc.queryForObject("select customer_sub from conversations where id = ?::uuid", String.class,
            guestConversation)).isEqualTo("user-1");
    }

    @Test
    void identifiedCustomerSkipsTheEmailQuestion() throws Exception {
        String session = identify(guest(), identityToken(secret, "user-1"));
        conversationId(session);

        assertThat(post(session, "/conversation/handoff", null)).bodyJson()
            .extractingPath("$.conversation.status").isEqualTo("WAITING");
    }

    @Test
    void sameCustomerOnAnotherDeviceJoinsTheirConversation() throws Exception {
        String laptop = identify(guest(), identityToken(secret, "user-1"));
        String existing = conversationId(laptop);
        post(laptop, "/conversation/messages", "{\"body\":\"from the laptop\"}");

        String phoneGuest = guest();
        String phoneConversation = conversationId(phoneGuest);
        post(phoneGuest, "/conversation/messages", "{\"body\":\"from the phone\"}");
        String phone = identify(phoneGuest, identityToken(secret, "user-1"));

        assertThat(conversationId(phone)).isEqualTo(existing);
        MvcTestResult messages = mvc.get().uri(BASE + "/conversation/messages")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + phone).exchange();
        assertThat(messages).bodyJson().extractingPath("$.items[*].body").asArray()
            .contains("from the laptop", "from the phone");
        assertThat(jdbc.queryForObject("select status from conversations where id = ?::uuid", String.class,
            phoneConversation)).isEqualTo("RESOLVED");
    }

    @Test
    void anotherCustomerOnTheSameBrowserStartsFresh() throws Exception {
        String browser = guest();
        String first = conversationId(identify(browser, identityToken(secret, "user-1")));

        String second = identify(browser, identityToken(secret, "user-2"));

        assertThat(conversationId(second)).isNotEqualTo(first);
        assertThat(jdbc.queryForList("select customer_sub from conversations where tenant_id = ? order by created_at",
            String.class, tenantId)).isEqualTo(List.of("user-1", "user-2"));
    }
}
