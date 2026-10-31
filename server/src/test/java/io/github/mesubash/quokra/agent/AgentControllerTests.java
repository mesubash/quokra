package io.github.mesubash.quokra.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.mesubash.quokra.TestcontainersConfiguration;
import io.github.mesubash.quokra.attachment.ImagesTests;
import io.github.mesubash.quokra.conversation.ConversationService;
import io.github.mesubash.quokra.conversation.Enums.HandoffReason;
import io.github.mesubash.quokra.conversation.Enums.SenderType;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "quokra.dev.agent-tokens=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
@RecordApplicationEvents
class AgentControllerTests {

    @Autowired
    ApplicationEvents published;

    static final String BASE = "/api/v1/agent";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ConversationService service;

    @Autowired
    DevAgentTokens devTokens;

    UUID tenant = UUID.randomUUID();
    UUID otherTenant = UUID.randomUUID();
    String alice;
    String bob;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("""
            insert into tenants (id, slug, display_name, publishable_key, bot_config)
            values (?, 'acme', 'Acme Inc', 'pk_test_acme', 'test'),
                   (?, 'globex', 'Globex', 'pk_test_globex', 'test')
            """, tenant, otherTenant);
        jdbc.update("""
            insert into tenant_agents (tenant_id, agent_sub)
            values (?, 'alice'), (?, 'bob'), (?, 'carol')
            """, tenant, tenant, otherTenant);
        alice = devTokens.sign("alice", "Alice", List.of("SUPPORT_AGENT"));
        bob = devTokens.sign("bob", "Bob", List.of("SUPPORT_AGENT"));
    }

    UUID waiting(UUID tenantId, HandoffReason reason) {
        UUID id = service.startOrResume(tenantId, UUID.randomUUID()).conversation().getId();
        service.addContact(id, "Pat", "pat-" + id + "@example.test");
        service.handoff(id, reason, SenderType.CUSTOMER);
        return id;
    }

    MvcTestResult get(String token, String uri) {
        return mvc.get().uri(BASE + uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    MvcTestResult post(String token, String uri, String json) {
        var request = mvc.post().uri(BASE + uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (json != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return request.exchange();
    }

    @Test
    void requiresAToken() {
        assertThat(mvc.get().uri(BASE + "/me")).hasStatus(HttpStatus.UNAUTHORIZED)
            .bodyJson().extractingPath("$.error.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void requiresTheAgentRole() throws Exception {
        String noRole = devTokens.sign("alice", "Alice", List.of("SOMETHING_ELSE"));

        assertThat(get(noRole, "/tenants")).hasStatus(HttpStatus.FORBIDDEN)
            .bodyJson().extractingPath("$.error.code").isEqualTo("FORBIDDEN");
        // /me needs no role (company admins can come from the token alone); it says what they may use.
        assertThat(get(noRole, "/me")).bodyJson().extractingPath("$.agent").isEqualTo(false);
    }

    @Test
    void meListsMyTenants() {
        MvcTestResult result = get(alice, "/me");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("Alice");
        assertThat(result).bodyJson().extractingPath("$.tenants").isEqualTo(List.of("acme"));
    }

    @Test
    void queueShowsUrgentFirstAndOnlyMyTenants() {
        UUID normal = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);
        UUID urgent = waiting(tenant, HandoffReason.URGENT);
        waiting(otherTenant, HandoffReason.URGENT);

        MvcTestResult result = get(alice, "/conversations?status=WAITING");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.items[*].id")
            .isEqualTo(List.of(urgent.toString(), normal.toString()));
        assertThat(result).bodyJson().extractingPath("$.items[0].lastMessagePreview")
            .isEqualTo("Handoff message.");
    }

    @Test
    void otherTenantsConversationsLookMissing() {
        UUID theirs = waiting(otherTenant, HandoffReason.CUSTOMER_REQUEST);

        assertThat(get(alice, "/conversations/" + theirs)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(post(alice, "/conversations/" + theirs + "/accept", null)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void acceptTakesTheConversation() {
        UUID id = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);

        MvcTestResult result = post(alice, "/conversations/" + id + "/accept", null);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.conversation.status").isEqualTo("HUMAN");
        assertThat(result).bodyJson().extractingPath("$.conversation.assignee.name").isEqualTo("Alice");
        assertThat(service.messages(id, null, 50).getLast().getBody()).isEqualTo("Alice joined the chat");
    }

    @Test
    void secondAgentCannotAcceptToo() {
        UUID id = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);
        post(alice, "/conversations/" + id + "/accept", null);

        assertThat(post(bob, "/conversations/" + id + "/accept", null)).hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.error.code").isEqualTo("ALREADY_ACCEPTED");
    }

    @Test
    void onlyTheAssigneeCanReply() {
        UUID id = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);
        post(alice, "/conversations/" + id + "/accept", null);

        assertThat(post(bob, "/conversations/" + id + "/messages", "{\"body\":\"Hi\"}"))
            .hasStatus(HttpStatus.FORBIDDEN).bodyJson().extractingPath("$.error.code").isEqualTo("NOT_ASSIGNEE");

        MvcTestResult reply = post(alice, "/conversations/" + id + "/messages",
            "{\"clientMessageId\":\"a-1\",\"body\":\"Hi, I'm Alice\"}");
        assertThat(reply).hasStatus(HttpStatus.CREATED);
        assertThat(reply).bodyJson().extractingPath("$.message.senderType").isEqualTo("AGENT");
        assertThat(reply).bodyJson().extractingPath("$.message.senderName").isEqualTo("Alice");
    }

    @Test
    void replyingBeforeAcceptingIsAConflict() {
        UUID id = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);

        assertThat(post(alice, "/conversations/" + id + "/messages", "{\"body\":\"Hi\"}"))
            .hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void customerRepliesDontWakeTheBot() {
        UUID id = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);
        post(alice, "/conversations/" + id + "/accept", null);

        service.addCustomerMessage(id, "c-1", "price", null);

        assertThat(service.messages(id, null, 50).getLast().getSenderType()).isEqualTo(SenderType.CUSTOMER);
    }

    @Test
    void assigneeResolves() {
        UUID id = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);
        post(alice, "/conversations/" + id + "/accept", null);

        assertThat(post(bob, "/conversations/" + id + "/resolve", null)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(post(alice, "/conversations/" + id + "/resolve", null)).hasStatusOk()
            .bodyJson().extractingPath("$.conversation.status").isEqualTo("RESOLVED");
    }

    @Test
    void anyAgentCanCloseAWaitingConversation() {
        UUID id = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);

        assertThat(post(bob, "/conversations/" + id + "/resolve", null)).hasStatusOk();
    }

    @Test
    void returnToBotHandsBackWithTheMenu() {
        UUID id = waiting(tenant, HandoffReason.URGENT);
        post(alice, "/conversations/" + id + "/accept", null);

        MvcTestResult result = post(alice, "/conversations/" + id + "/return-to-bot", null);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.conversation.status").isEqualTo("BOT");
        assertThat(result).bodyJson().extractingPath("$.conversation.assignee").isNull();
        assertThat(result).bodyJson().extractingPath("$.conversation.priority").isEqualTo("NORMAL");
        var last = service.messages(id, null, 50).getLast();
        assertThat(last.getSenderType()).isEqualTo(SenderType.BOT);
        assertThat(last.getOptions()).isNotEmpty();
    }

    @Test
    void detailIncludesHistoryAndTimeline() {
        UUID id = waiting(tenant, HandoffReason.URGENT);
        post(alice, "/conversations/" + id + "/accept", null);

        MvcTestResult result = get(alice, "/conversations/" + id);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.conversation.handoffReason").isEqualTo("URGENT");
        assertThat(result).bodyJson().extractingPath("$.events[*].type")
            .isEqualTo(List.of("CREATED", "CONTACT_ADDED", "HANDOFF", "ACCEPTED"));
        assertThat(result).bodyJson().extractingPath("$.events[3].actorName").isEqualTo("Alice");
    }

    @Test
    void myChatsListsWhatIAccepted() {
        UUID mine = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);
        UUID bobs = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);
        post(alice, "/conversations/" + mine + "/accept", null);
        post(bob, "/conversations/" + bobs + "/accept", null);

        assertThat(get(alice, "/conversations?status=HUMAN&assignee=me")).hasStatusOk()
            .bodyJson().extractingPath("$.items[*].id").isEqualTo(List.of(mine.toString()));
    }
    @Test
    void localDashboardsCanFetchDevTokens() {
        assertThat(mvc.options().uri("/dev/agent-token")
                .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
            .hasStatusOk()
            .hasHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5500");
    }

    @Test
    void otherSitesCantFetchDevTokens() {
        assertThat(mvc.options().uri("/dev/agent-token")
                .header(HttpHeaders.ORIGIN, "https://evil.test")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
            .hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void agentApiDoesNotAllowOtherOrigins() {
        assertThat(mvc.options().uri(BASE + "/conversations")
                .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
            .doesNotContainHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }
    UUID taken(String token) {
        UUID id = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);
        post(token, "/conversations/" + id + "/accept", null);
        return id;
    }

    @Test
    void blockClosesTheChatAndStopsTheContact() {
        UUID id = taken(alice);

        MvcTestResult result = post(alice, "/conversations/" + id + "/block", null);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.conversation.status").isEqualTo("RESOLVED");
        assertThat(result).bodyJson().extractingPath("$.conversation.customer.blocked").isEqualTo(true);
        assertThat(post(alice, "/conversations/" + id + "/reopen", null)).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void unblockLetsTheContactBack() throws Exception {
        UUID id = taken(alice);
        post(alice, "/conversations/" + id + "/block", null);
        String contactId = JsonPath.read(get(alice, "/conversations/" + id).getResponse().getContentAsString(),
            "$.conversation.customer.contactId");

        assertThat(post(alice, "/contacts/" + contactId + "/unblock", null)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(get(alice, "/conversations/" + id)).bodyJson()
            .extractingPath("$.conversation.customer.blocked").isEqualTo(false);
    }

    @Test
    void agentsOfOtherTenantsCantUnblock() throws Exception {
        UUID id = taken(alice);
        post(alice, "/conversations/" + id + "/block", null);
        String contactId = JsonPath.read(get(alice, "/conversations/" + id).getResponse().getContentAsString(),
            "$.conversation.customer.contactId");
        String carol = devTokens.sign("carol", "Carol", List.of("SUPPORT_AGENT"));

        assertThat(post(carol, "/contacts/" + contactId + "/unblock", null)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void reopenGivesTheChatBackToTheAgent() {
        UUID id = taken(alice);
        post(alice, "/conversations/" + id + "/resolve", null);

        MvcTestResult result = post(bob, "/conversations/" + id + "/reopen", null);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.conversation.status").isEqualTo("HUMAN");
        assertThat(result).bodyJson().extractingPath("$.conversation.assignee.sub").isEqualTo("bob");
    }

    @Test
    void transferToAnotherAgent() {
        UUID id = taken(alice);
        get(bob, "/me"); // remembers Bob's name

        MvcTestResult result = post(alice, "/conversations/" + id + "/transfer", "{\"agentSub\":\"bob\"}");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.conversation.assignee.name").isEqualTo("Bob");
        assertThat(post(alice, "/conversations/" + id + "/messages", "{\"body\":\"hi\"}"))
            .hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void transferBackToTheQueueKeepsThePriority() {
        UUID id = waiting(tenant, HandoffReason.URGENT);
        post(alice, "/conversations/" + id + "/accept", null);

        MvcTestResult result = post(alice, "/conversations/" + id + "/transfer", "{}");

        assertThat(result).bodyJson().extractingPath("$.conversation.status").isEqualTo("WAITING");
        assertThat(result).bodyJson().extractingPath("$.conversation.priority").isEqualTo("URGENT");
        assertThat(result).bodyJson().extractingPath("$.conversation.assignee").isNull();
    }

    @Test
    void cantTransferToSomeoneOutsideTheTenant() {
        UUID id = taken(alice);

        assertThat(post(alice, "/conversations/" + id + "/transfer", "{\"agentSub\":\"carol\"}"))
            .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(post(bob, "/conversations/" + id + "/transfer", "{}")).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void listsTheTenantsAgents() {
        UUID id = taken(alice);
        get(alice, "/me");

        assertThat(get(alice, "/conversations/" + id + "/agents")).bodyJson()
            .extractingPath("$.items[*].sub").isEqualTo(List.of("alice", "bob"));
    }

    @Test
    void assigneeSendsAnImageAndOtherAgentsOfTheTenantCanSeeIt() throws Exception {
        jdbc.update("update tenants set attachments_enabled = true");
        UUID id = taken(alice);
        var file = new MockMultipartFile("file", "steps.png", "image/png", ImagesTests.PNG);

        assertThat(mvc.post().uri(BASE + "/conversations/" + id + "/attachments")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bob).multipart().file(file).exchange())
            .hasStatus(HttpStatus.FORBIDDEN);
        MvcTestResult sent = mvc.post().uri(BASE + "/conversations/" + id + "/attachments")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice).multipart().file(file).exchange();
        assertThat(sent).hasStatus(HttpStatus.CREATED);
        String messageId = JsonPath.read(sent.getResponse().getContentAsString(), "$.message.id");

        assertThat(get(bob, "/conversations/" + id + "/messages/" + messageId + "/attachment")).hasStatusOk();
        String carol = devTokens.sign("carol", "Carol", List.of("SUPPORT_AGENT"));
        assertThat(get(carol, "/conversations/" + id + "/messages/" + messageId + "/attachment"))
            .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void savedRepliesComeFromTheConversationsTenant() throws Exception {
        jdbc.update("""
            insert into canned_replies (id, tenant_id, title, body)
            values (?, ?, 'Refunds', 'Hi {name}'), (?, ?, 'Other', 'Not for acme')
            """, UUID.randomUUID(), tenant, UUID.randomUUID(), otherTenant);
        UUID id = taken(alice);

        assertThat(get(alice, "/conversations/" + id + "/replies")).bodyJson()
            .extractingPath("$.items[*].title").isEqualTo(List.of("Refunds"));
        String carol = devTokens.sign("carol", "Carol", List.of("SUPPORT_AGENT"));
        assertThat(get(carol, "/conversations/" + id + "/replies")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void theChatShowsWhetherAnyoneOfTheTeamIsOnline() {
        String widget = "/api/v1/widget/tenants/pk_test_acme";
        assertThat(mvc.get().uri(widget)).bodyJson().extractingPath("$.teamOnline").isEqualTo(false);

        assertThat(post(alice, "/presence", null)).bodyJson().extractingPath("$.status").isEqualTo("ONLINE");
        assertThat(mvc.get().uri(widget)).bodyJson().extractingPath("$.teamOnline").isEqualTo(true);
        UUID id = taken(alice);
        assertThat(get(alice, "/conversations/" + id + "/agents")).bodyJson()
            .extractingPath("$.items[?(@.sub == 'alice')].online").isEqualTo(List.of(true));

        assertThat(post(alice, "/presence", "{\"status\":\"AWAY\"}")).bodyJson()
            .extractingPath("$.status").isEqualTo("AWAY");
        assertThat(post(alice, "/presence", null)).bodyJson().extractingPath("$.status").isEqualTo("AWAY");
        assertThat(mvc.get().uri(widget)).bodyJson().extractingPath("$.teamOnline").isEqualTo(false);
    }

    @Test
    void notesAreForAgentsOnly() {
        UUID id = taken(alice);

        assertThat(post(bob, "/conversations/" + id + "/notes", "{\"body\":\"VIP, be nice\"}"))
            .hasStatus(HttpStatus.CREATED);

        assertThat(get(alice, "/conversations/" + id)).bodyJson().extractingPath("$.notes[0].body")
            .isEqualTo("VIP, be nice");
        assertThat(get(alice, "/conversations/" + id)).bodyJson().extractingPath("$.messages[*].body")
            .asArray().doesNotContain("VIP, be nice");
    }
    @Test
    void queuePagesFollowEachOtherWithoutGapsOrRepeats() throws Exception {
        UUID urgent = waiting(tenant, HandoffReason.URGENT);
        UUID first = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);
        UUID second = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);

        String page1 = get(alice, "/conversations?status=WAITING&limit=2").getResponse().getContentAsString();
        String cursor = JsonPath.read(page1, "$.nextCursor");
        MvcTestResult page2 = get(alice, "/conversations?status=WAITING&limit=2&cursor=" + cursor);

        assertThat(JsonPath.<List<String>>read(page1, "$.items[*].id"))
            .containsExactly(urgent.toString(), first.toString());
        assertThat(page2).bodyJson().extractingPath("$.items[*].id").isEqualTo(List.of(second.toString()));
        assertThat(page2).bodyJson().extractingPath("$.nextCursor").isNull();
    }

    @Test
    void recentListPagesByLastMessage() throws Exception {
        UUID older = taken(alice);
        UUID newer = taken(alice);

        String page1 = get(alice, "/conversations?status=HUMAN&assignee=me&limit=1").getResponse().getContentAsString();
        MvcTestResult page2 = get(alice, "/conversations?status=HUMAN&assignee=me&limit=1&cursor="
            + JsonPath.read(page1, "$.nextCursor"));

        assertThat(JsonPath.<List<String>>read(page1, "$.items[*].id")).containsExactly(newer.toString());
        assertThat(page2).bodyJson().extractingPath("$.items[*].id").isEqualTo(List.of(older.toString()));
    }

    @Test
    void badCursorIsRejected() {
        assertThat(get(alice, "/conversations?status=WAITING&cursor=nope")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void countsWaitingAndMine() {
        waiting(tenant, HandoffReason.CUSTOMER_REQUEST);
        waiting(otherTenant, HandoffReason.CUSTOMER_REQUEST);
        taken(alice);

        MvcTestResult result = get(alice, "/conversations/counts");

        assertThat(result).bodyJson().extractingPath("$.waiting").isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.mine").isEqualTo(1);
    }

    @Test
    void countsUnreadMessagesInMyChats() {
        UUID id = taken(alice);
        service.addCustomerMessage(id, "c-7", "still there?", null);
        service.addCustomerMessage(id, "c-8", "hello?", null);

        // Two from earlier (asking for a person, the email) plus these two: Alice hasn't opened it.
        assertThat(get(alice, "/conversations/counts")).bodyJson().extractingPath("$.myUnread").asNumber()
            .satisfies(n -> assertThat(n.intValue()).isGreaterThanOrEqualTo(2));
        assertThat(get(bob, "/conversations/counts")).bodyJson().extractingPath("$.myUnread").isEqualTo(0);
    }

    @Test
    void unreadCountsCustomerMessagesUntilTheAgentHasReadThem() throws Exception {
        UUID id = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);
        service.addCustomerMessage(id, "c-1", "are you there?", null);

        String list = get(alice, "/conversations?status=WAITING").getResponse().getContentAsString();
        int before = JsonPath.<Integer>read(list, "$.items[0].unreadCount");
        String detail = get(alice, "/conversations/" + id).getResponse().getContentAsString();
        String lastId = JsonPath.read(detail, "$.messages[-1].id");

        assertThat(before).isPositive();
        assertThat(post(alice, "/conversations/" + id + "/read", "{\"messageId\":\"" + lastId + "\"}"))
            .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(get(alice, "/conversations?status=WAITING")).bodyJson()
            .extractingPath("$.items[0].unreadCount").isEqualTo(0);
        // Bob hasn't read anything.
        assertThat(get(bob, "/conversations?status=WAITING")).bodyJson()
            .extractingPath("$.items[0].unreadCount").isEqualTo(before);

        service.addCustomerMessage(id, "c-2", "hello?", null);
        assertThat(get(alice, "/conversations?status=WAITING")).bodyJson()
            .extractingPath("$.items[0].unreadCount").isEqualTo(1);
    }

    @Test
    void markingAnOlderMessageReadDoesNotGoBack() throws Exception {
        UUID id = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);
        String detail = get(alice, "/conversations/" + id).getResponse().getContentAsString();
        String first = JsonPath.read(detail, "$.messages[0].id");
        String last = JsonPath.read(detail, "$.messages[-1].id");

        post(alice, "/conversations/" + id + "/read", "{\"messageId\":\"" + last + "\"}");
        post(alice, "/conversations/" + id + "/read", "{\"messageId\":\"" + first + "\"}");

        assertThat(get(alice, "/conversations?status=WAITING")).bodyJson()
            .extractingPath("$.items[0].unreadCount").isEqualTo(0);
    }

    @Test
    void searchFindsByNameEmailOrMessage() {
        UUID byName = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);
        UUID byMessage = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);
        waiting(tenant, HandoffReason.CUSTOMER_REQUEST);
        jdbc.update("update conversations set customer_name = 'Robin Fischer' where id = ?", byName);
        service.addCustomerMessage(byMessage, "c-1", "My invoice 100% is wrong", null);

        assertThat(get(alice, "/conversations?status=WAITING&q=fISCHER")).bodyJson()
            .extractingPath("$.items[*].id").isEqualTo(List.of(byName.toString()));
        assertThat(get(alice, "/conversations?status=WAITING&q=invoice")).bodyJson()
            .extractingPath("$.items[*].id").isEqualTo(List.of(byMessage.toString()));
        assertThat(get(alice, "/conversations?status=WAITING&q=" + "pat-" + byName)).bodyJson()
            .extractingPath("$.items[*].id").isEqualTo(List.of(byName.toString()));
    }

    @Test
    void searchTakesWildcardsLiterally() {
        UUID percent = waiting(tenant, HandoffReason.CUSTOMER_REQUEST);
        waiting(tenant, HandoffReason.CUSTOMER_REQUEST);
        service.addCustomerMessage(percent, "c-1", "50% off?", null);

        assertThat(get(alice, "/conversations?status=WAITING&q=%")).bodyJson()
            .extractingPath("$.items[*].id").isEqualTo(List.of(percent.toString()));
        assertThat(get(alice, "/conversations?status=WAITING&q=_")).bodyJson()
            .extractingPath("$.items").asArray().isEmpty();
    }

    @Test
    void searchIsLimitedInLength() {
        assertThat(get(alice, "/conversations?status=WAITING&q=" + "x".repeat(101))).hasStatus(HttpStatus.BAD_REQUEST);
    }

    UUID chatWithEmail(String email) {
        UUID id = service.startOrResume(tenant, UUID.randomUUID()).conversation().getId();
        service.addContact(id, "Pat", email);
        return id;
    }

    @Test
    void historyListsTheSameCustomersOtherChats() {
        UUID first = chatWithEmail("returning@example.test");
        service.addCustomerMessage(first, "c-1", "first visit", null);
        UUID second = chatWithEmail("RETURNING@example.test");
        chatWithEmail("someone-else@example.test");

        MvcTestResult result = get(alice, "/conversations/" + second + "/history");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.items[*].id").isEqualTo(List.of(first.toString()));
        assertThat(result).bodyJson().extractingPath("$.items[0].lastMessagePreview").isEqualTo("Fallback message.");
    }

    @Test
    void anonymousGuestsHaveNoHistory() {
        UUID guest = service.startOrResume(tenant, UUID.randomUUID()).conversation().getId();
        service.startOrResume(tenant, UUID.randomUUID());

        assertThat(get(alice, "/conversations/" + guest + "/history")).bodyJson()
            .extractingPath("$.items").asArray().isEmpty();
    }

    @Test
    void historyIsOnlyForAgentsOfTheTenant() throws Exception {
        UUID id = chatWithEmail("returning@example.test");
        String carol = devTokens.sign("carol", "Carol", List.of("SUPPORT_AGENT"));

        assertThat(get(carol, "/conversations/" + id + "/history")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void transferAndNotesAnnounceThemselvesForNotifications() {
        UUID id = taken(alice);
        get(bob, "/me");

        post(alice, "/conversations/" + id + "/transfer", "{\"agentSub\":\"bob\"}");
        post(alice, "/conversations/" + id + "/notes", "{\"body\":\"Over to you\"}");

        assertThat(published.stream(io.github.mesubash.quokra.conversation.ConversationTransferred.class))
            .singleElement().satisfies(e -> {
                assertThat(e.to().sub()).isEqualTo("bob");
                assertThat(e.byName()).isEqualTo("Alice");
            });
        assertThat(published.stream(io.github.mesubash.quokra.conversation.NoteCreated.class))
            .singleElement().satisfies(e -> assertThat(e.assigneeSub()).isEqualTo("bob"));
    }
}
