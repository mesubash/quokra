package io.github.mesubash.quokra.realtime;

import io.github.mesubash.quokra.conversation.Conversation;
import io.github.mesubash.quokra.conversation.ConversationService;
import io.github.mesubash.quokra.security.AgentJwtConverter;
import io.github.mesubash.quokra.session.SessionTokens;
import io.github.mesubash.quokra.session.SessionTokens.Session;
import io.github.mesubash.quokra.tenant.TenantAgents;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.stereotype.Component;

// Checks the token on CONNECT (a customer's chat session or an agent's access token)
// and that every SUBSCRIBE is to something that person may see.
@Component
class StompAuthInterceptor implements ChannelInterceptor {

    static final String CONVERSATION_TOPIC = "/topic/conversations/";
    static final String TENANT_TOPIC = "/topic/tenants/";
    // Agent-only notes of a conversation: /topic/conversations/{id}/notes
    static final String NOTES_SUFFIX = "/notes";
    static final String QUEUE_SUFFIX = "/queue";

    static String queueTopic(UUID tenantId) {
        return TENANT_TOPIC + tenantId + QUEUE_SUFFIX;
    }

    private static final String AGENT_AUTHORITY = "ROLE_" + AgentJwtConverter.AGENT_ROLE;

    private final SessionTokens sessionTokens;
    private final ConversationService conversations;
    private final ObjectProvider<JwtDecoder> jwtDecoder;
    private final AgentJwtConverter agentConverter;
    private final TenantAgents tenantAgents;

    StompAuthInterceptor(SessionTokens sessionTokens, ConversationService conversations,
                         ObjectProvider<JwtDecoder> jwtDecoder, AgentJwtConverter agentConverter,
                         TenantAgents tenantAgents) {
        this.sessionTokens = sessionTokens;
        this.conversations = conversations;
        this.jwtDecoder = jwtDecoder;
        this.agentConverter = agentConverter;
        this.tenantAgents = tenantAgents;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor stomp = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (stomp == null || stomp.getCommand() == null) {
            return message;
        }
        switch (stomp.getCommand()) {
            case CONNECT -> stomp.setUser(authenticate(stomp));
            case SUBSCRIBE -> checkSubscription(stomp.getUser(), stomp.getDestination());
            case SEND -> throw new AccessDeniedException("Sending over the socket isn't supported");
            default -> { }
        }
        return message;
    }

    private Principal authenticate(StompHeaderAccessor stomp) {
        String header = stomp.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new AccessDeniedException("Missing token");
        }
        String token = header.substring("Bearer ".length());

        Optional<Session> session = sessionTokens.verify(token);
        if (session.isPresent()) {
            return new PreAuthenticatedAuthenticationToken(session.get(), token,
                List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));
        }

        JwtDecoder decoder = jwtDecoder.getIfAvailable();
        if (decoder != null) {
            try {
                AbstractAuthenticationToken agent = agentConverter.convert(decoder.decode(token));
                if (agent.getAuthorities().stream().anyMatch(a -> AGENT_AUTHORITY.equals(a.getAuthority()))) {
                    return agent;
                }
            } catch (JwtException e) {
                // Not a valid agent token either.
            }
        }
        throw new AccessDeniedException("Invalid token");
    }

    private void checkSubscription(Principal user, String destination) {
        if (destination == null) {
            throw new AccessDeniedException("Unknown destination");
        }
        if (user instanceof PreAuthenticatedAuthenticationToken auth && auth.getPrincipal() instanceof Session session) {
            checkCustomer(session, destination);
        } else if (user instanceof JwtAuthenticationToken agent) {
            checkAgent(agent.getToken().getSubject(), destination);
        } else {
            throw new AccessDeniedException("Not connected");
        }
    }

    // Customers may only follow their own conversation.
    private void checkCustomer(Session session, String destination) {
        UUID id = idAfter(destination, CONVERSATION_TOPIC, "");
        Optional<Conversation> own = conversations.current(session.tenantId(), session.visitorId());
        if (own.isEmpty() || !own.get().getId().equals(id)) {
            throw new AccessDeniedException("Not your conversation");
        }
    }

    // Agents may follow their tenants' queues and any conversation of those tenants.
    private void checkAgent(String agentSub, String destination) {
        List<UUID> tenants = tenantAgents.tenantIdsFor(agentSub);
        UUID tenantId;
        if (destination.startsWith(TENANT_TOPIC)) {
            tenantId = idAfter(destination, TENANT_TOPIC, QUEUE_SUFFIX);
        } else {
            UUID conversationId = idAfter(destination, CONVERSATION_TOPIC,
                destination.endsWith(NOTES_SUFFIX) ? NOTES_SUFFIX : "");
            try {
                tenantId = conversations.get(conversationId).getTenantId();
            } catch (RuntimeException e) {
                throw new AccessDeniedException("Unknown conversation");
            }
        }
        if (!tenants.contains(tenantId)) {
            throw new AccessDeniedException("Not one of your tenants");
        }
    }

    private static UUID idAfter(String destination, String prefix, String suffix) {
        if (!destination.startsWith(prefix) || !destination.endsWith(suffix)) {
            throw new AccessDeniedException("Unknown destination");
        }
        try {
            return UUID.fromString(destination.substring(prefix.length(), destination.length() - suffix.length()));
        } catch (IllegalArgumentException e) {
            throw new AccessDeniedException("Unknown destination");
        }
    }
}
