package io.github.mesubash.quokra.realtime;

import io.github.mesubash.quokra.conversation.Conversation;
import io.github.mesubash.quokra.conversation.ConversationService;
import io.github.mesubash.quokra.session.SessionTokens;
import io.github.mesubash.quokra.session.SessionTokens.Session;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.stereotype.Component;

// Checks the token on CONNECT and that every SUBSCRIBE is to the visitor's own conversation.
@Component
class StompAuthInterceptor implements ChannelInterceptor {

    static final String CONVERSATION_TOPIC = "/topic/conversations/";

    private final SessionTokens sessionTokens;
    private final ConversationService conversations;

    StompAuthInterceptor(SessionTokens sessionTokens, ConversationService conversations) {
        this.sessionTokens = sessionTokens;
        this.conversations = conversations;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor stomp = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (stomp == null || stomp.getCommand() == null) {
            return message;
        }
        switch (stomp.getCommand()) {
            case CONNECT -> stomp.setUser(authenticate(stomp));
            case SUBSCRIBE -> checkSubscription(stomp);
            case SEND -> throw new AccessDeniedException("Sending over the socket isn't supported");
            default -> { }
        }
        return message;
    }

    private PreAuthenticatedAuthenticationToken authenticate(StompHeaderAccessor stomp) {
        String header = stomp.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new AccessDeniedException("Missing token");
        }
        String token = header.substring("Bearer ".length());
        Session session = sessionTokens.verify(token).orElseThrow(() -> new AccessDeniedException("Invalid token"));
        return new PreAuthenticatedAuthenticationToken(session, token, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));
    }

    private void checkSubscription(StompHeaderAccessor stomp) {
        Session session = session(stomp).orElseThrow(() -> new AccessDeniedException("Not connected"));
        String destination = stomp.getDestination();
        if (destination == null || !destination.startsWith(CONVERSATION_TOPIC)) {
            throw new AccessDeniedException("Unknown destination");
        }
        UUID id;
        try {
            id = UUID.fromString(destination.substring(CONVERSATION_TOPIC.length()));
        } catch (IllegalArgumentException e) {
            throw new AccessDeniedException("Unknown destination");
        }
        Optional<Conversation> own = conversations.current(session.tenantId(), session.visitorId());
        if (own.isEmpty() || !own.get().getId().equals(id)) {
            throw new AccessDeniedException("Not your conversation");
        }
    }

    private static Optional<Session> session(StompHeaderAccessor stomp) {
        return stomp.getUser() instanceof PreAuthenticatedAuthenticationToken auth
            && auth.getPrincipal() instanceof Session session ? Optional.of(session) : Optional.empty();
    }
}
