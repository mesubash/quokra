package io.github.mesubash.quokra.widget;

import io.github.mesubash.quokra.attachment.Images;
import io.github.mesubash.quokra.common.ApiException;
import io.github.mesubash.quokra.common.RateLimiter;
import io.github.mesubash.quokra.conversation.Conversation;
import io.github.mesubash.quokra.conversation.ConversationService;
import io.github.mesubash.quokra.conversation.CustomerConversationView;
import io.github.mesubash.quokra.conversation.Enums.HandoffReason;
import io.github.mesubash.quokra.conversation.Enums.SenderType;
import io.github.mesubash.quokra.conversation.Message;
import io.github.mesubash.quokra.conversation.MessageView;
import io.github.mesubash.quokra.conversation.Status;
import io.github.mesubash.quokra.realtime.TypingSignals;
import io.github.mesubash.quokra.session.SessionTokens.Session;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

// Customer-side conversation endpoints. A session only ever sees its own visitor's conversation.
@RestController
@RequestMapping("/api/v1/widget/conversation")
public class WidgetConversationController {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    public record CurrentResponse(CustomerConversationView conversation) {}

    public record StartResponse(CustomerConversationView conversation, List<MessageView> messages) {}

    public record MessagePage(List<MessageView> items, UUID nextCursor) {}

    // replyTo: an earlier message this one answers (optional).
    public record SendRequest(@Size(max = 64) String clientMessageId, String body, @Size(max = 64) String optionId,
                              UUID replyTo) {}

    public record SendResponse(MessageView message, CustomerConversationView conversation) {}

    private final ConversationService service;
    private final RateLimiter rateLimiter;
    private final TypingSignals typing;

    public WidgetConversationController(ConversationService service, RateLimiter rateLimiter, TypingSignals typing) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.typing = typing;
    }

    @GetMapping
    public CurrentResponse current(@AuthenticationPrincipal Session session) {
        return new CurrentResponse(service.current(session.tenantId(), session.visitorId(), session.customerSub())
            .map(CustomerConversationView::of)
            .orElse(null));
    }

    @PostMapping
    public ResponseEntity<StartResponse> start(@AuthenticationPrincipal Session session) {
        Conversation conversation;
        boolean created;
        try {
            var started = service.startOrResume(session.tenantId(), session.visitorId(), session.customer());
            conversation = started.conversation();
            created = started.created();
        } catch (DataIntegrityViolationException e) {
            // Two starts at the same moment: the other request created it.
            conversation = openConversation(session);
            created = false;
        }
        List<MessageView> messages = service.messages(conversation.getId(), null, DEFAULT_LIMIT).stream()
            .map(MessageView::of).toList();
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK)
            .body(new StartResponse(CustomerConversationView.of(conversation), messages));
    }

    // "Start a new chat": closes the current one (even with an agent) and starts over with the greeting.
    @PostMapping("/new")
    @ResponseStatus(HttpStatus.CREATED)
    public StartResponse startNew(@AuthenticationPrincipal Session session) {
        rateLimiter.check("messages:" + session.visitorId(), RateLimiter.MESSAGES_PER_MINUTE);
        Conversation conversation = service.startNew(session.tenantId(), session.visitorId(), session.customer());
        List<MessageView> messages = service.messages(conversation.getId(), null, DEFAULT_LIMIT).stream()
            .map(MessageView::of).toList();
        return new StartResponse(CustomerConversationView.of(conversation), messages);
    }

    @GetMapping("/messages")
    public MessagePage messages(@AuthenticationPrincipal Session session,
                                @RequestParam(required = false) UUID after,
                                @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        int size = Math.clamp(limit, 1, MAX_LIMIT);
        List<MessageView> items = service.messages(openConversation(session).getId(), after, size).stream()
            .map(MessageView::of).toList();
        UUID next = items.size() == size ? items.getLast().id() : null;
        return new MessagePage(items, next);
    }

    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public SendResponse send(@AuthenticationPrincipal Session session, @Valid @RequestBody SendRequest request) {
        rateLimiter.check("messages:" + session.visitorId(), RateLimiter.MESSAGES_PER_MINUTE);
        UUID id = openConversation(session).getId();
        Message message = service.addCustomerMessage(id, request.clientMessageId(), request.body(), request.optionId(),
            request.replyTo());
        return new SendResponse(MessageView.of(message), CustomerConversationView.of(service.get(id)));
    }

    // An image (multipart field "file"). The message carries its details; the file has its own URL below.
    @PostMapping(path = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SendResponse sendImage(@AuthenticationPrincipal Session session, @RequestParam MultipartFile file,
                                  @RequestParam(required = false) @Size(max = 64) String clientMessageId,
                                  @RequestParam(required = false) UUID replyTo)
            throws IOException {
        rateLimiter.check("messages:" + session.visitorId(), RateLimiter.MESSAGES_PER_MINUTE);
        UUID id = openConversation(session).getId();
        Message message = service.addCustomerImage(id, clientMessageId, file.getOriginalFilename(), file.getBytes(),
            replyTo);
        return new SendResponse(MessageView.of(message), CustomerConversationView.of(service.get(id)));
    }

    @GetMapping("/messages/{messageId}/attachment")
    public ResponseEntity<byte[]> image(@AuthenticationPrincipal Session session, @PathVariable UUID messageId) {
        var image = service.image(openConversation(session).getId(), messageId);
        return Images.response(image.bytes(), image.contentType(), image.name());
    }

    // The customer is typing; the agent sees it for a few seconds. Nothing is stored.
    @PostMapping("/typing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void typing(@AuthenticationPrincipal Session session) {
        rateLimiter.check("typing:" + session.visitorId(), RateLimiter.TYPING_PER_MINUTE);
        typing.typing(openConversation(session).getId(), SenderType.CUSTOMER, null);
    }

    @PostMapping("/handoff")
    public CurrentResponse handoff(@AuthenticationPrincipal Session session) {
        Conversation conversation = openConversation(session);
        // Already with the team: nothing to do, so a retry is harmless.
        if (conversation.getStatus() == Status.WAITING || conversation.getStatus() == Status.HUMAN) {
            return new CurrentResponse(CustomerConversationView.of(conversation));
        }
        return new CurrentResponse(CustomerConversationView.of(
            service.handoff(conversation.getId(), HandoffReason.CUSTOMER_REQUEST, SenderType.CUSTOMER)));
    }

    private Conversation openConversation(Session session) {
        return service.current(session.tenantId(), session.visitorId(), session.customerSub())
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "NO_OPEN_CONVERSATION", "Start a conversation first"));
    }
}
