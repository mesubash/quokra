package io.github.mesubash.quokra.widget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.mesubash.quokra.TestcontainersConfiguration;
import io.github.mesubash.quokra.conversation.AgentRef;
import io.github.mesubash.quokra.conversation.ConversationService;
import io.github.mesubash.quokra.conversation.Enums.HandoffReason;
import io.github.mesubash.quokra.conversation.Enums.SenderType;
import io.github.mesubash.quokra.session.SessionTokens;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"spring.mail.host=smtp.example.test", "quokra.mail.from=Support <support@example.test>"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class TranscriptTests {

    static final String BASE = "/api/v1/widget/conversation";

    @MockitoBean
    JavaMailSender sender;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionTokens sessionTokens;

    @Autowired
    ConversationService service;

    @Autowired
    EntityManager entityManager;

    UUID tenant = UUID.randomUUID();
    String pk = "pk_" + tenant;
    String token;
    AgentRef sam = new AgentRef("sam-" + tenant, "Sam");

    @BeforeEach
    void setUp() {
        jdbc.update("""
            insert into tenants (id, slug, display_name, publishable_key, bot_config)
            values (?, ?, 'Acme', ?, 'test')
            """, tenant, "acme-" + tenant, pk);
        token = sessionTokens.issue(tenant).token();
    }

    UUID chat(String email) {
        UUID id = UUID.fromString(JsonPath.read(content(post(BASE, null)), "$.conversation.id"));
        if (email != null) {
            service.addContact(id, "Pat", email);
        }
        return id;
    }

    @Test
    void widgetConfigSaysTranscriptsWork() {
        assertThat(mvc.get().uri("/api/v1/widget/tenants/" + pk)).hasStatusOk()
            .bodyJson().extractingPath("$.transcripts").isEqualTo(true);
    }

    @Test
    void emailsTheChatWithoutNotesToTheCustomersOwnEmail() {
        UUID id = chat("pat.one@example.test");
        post(BASE + "/messages", "{\"clientMessageId\":\"c-1\",\"body\":\"Where is my parcel?\"}");
        service.handoff(id, HandoffReason.CUSTOMER_REQUEST, SenderType.CUSTOMER);
        service.accept(id, sam);
        service.addNote(id, sam, "Private: check the courier");
        service.addAgentMessage(id, sam, "a-1", "It's on its way.");
        service.addAgentMessage(id, sam, "a-2", "picture");
        entityManager.flush();
        jdbc.update("update messages set attachment_key = 'k' where client_message_id = 'a-2'");
        entityManager.clear();
        assertThat(get(BASE)).bodyJson().extractingPath("$.conversation.hasEmail").isEqualTo(true);

        // An address sent with the request is ignored.
        MvcTestResult result = post(BASE + "/transcript", "{\"email\":\"someone-else@example.test\"}");

        assertThat(result).hasStatus(HttpStatus.ACCEPTED)
            .bodyJson().extractingPath("$.sentTo").isEqualTo("p…@example.test");
        var mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(mail.capture());
        assertThat(mail.getValue().getTo()).containsExactly("pat.one@example.test");
        assertThat(mail.getValue().getFrom()).isEqualTo("Support <support@example.test>");
        assertThat(mail.getValue().getSubject()).startsWith("Your chat with Acme on ");
        String text = mail.getValue().getText();
        assertThat(text).containsPattern("You \\(\\d\\d:\\d\\d\\): Where is my parcel\\?")
            .containsPattern("Sam \\(\\d\\d:\\d\\d\\): It's on its way.")
            .containsPattern("Sam \\(\\d\\d:\\d\\d\\): \\[image]")
            .contains("\nSam joined the chat\n")
            .doesNotContain("Private").doesNotContain("picture");
        assertThat(text.indexOf("Where is my parcel")).isLessThan(text.indexOf("It's on its way"));
    }

    @Test
    void refusedWithoutACustomerEmail() {
        chat(null);

        assertThat(post(BASE + "/transcript", "{\"email\":\"someone@example.test\"}")).hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.error.code").isEqualTo("NO_CUSTOMER_EMAIL");
        verify(sender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void threeAnHourPerChat() {
        chat("pat.two@example.test");
        for (int i = 0; i < 3; i++) {
            assertThat(post(BASE + "/transcript", null)).hasStatus(HttpStatus.ACCEPTED);
        }

        assertThat(post(BASE + "/transcript", null)).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
            .bodyJson().extractingPath("$.error.code").isEqualTo("RATE_LIMITED");
    }

    @Test
    void threeADayToTheSameAddressAcrossChats() {
        // Fresh sessions and chats each time, from an address no other test uses, so only the per-address limit can bite.
        for (int i = 0; i < 4; i++) {
            token = sessionTokens.issue(tenant).token();
            chat("Same.Person@example.test");
            var result = mvc.post().uri(BASE + "/transcript").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .with(r -> {
                    r.setRemoteAddr("10.9.9.9");
                    return r;
                }).exchange();
            if (i < 3) {
                assertThat(result).hasStatus(HttpStatus.ACCEPTED);
            } else {
                assertThat(result).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
            }
        }
    }

    private MvcTestResult get(String uri) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private MvcTestResult post(String uri, String json) {
        var request = mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (json != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return request.exchange();
    }

    private static String content(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
