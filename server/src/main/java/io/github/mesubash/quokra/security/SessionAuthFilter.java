package io.github.mesubash.quokra.security;

import io.github.mesubash.quokra.session.SessionTokens;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

// Reads the chat session token on widget conversation endpoints.
class SessionAuthFilter extends OncePerRequestFilter {

    static final String PATH = "/api/v1/widget/conversation";
    static final String ROLE = "VISITOR";

    private final SessionTokens sessionTokens;

    SessionAuthFilter(SessionTokens sessionTokens) {
        this.sessionTokens = sessionTokens;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals(PATH) || path.startsWith(PATH + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length());
            sessionTokens.verify(token).ifPresent(session -> SecurityContextHolder.getContext().setAuthentication(
                new PreAuthenticatedAuthenticationToken(session, token, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE)))));
        }
        chain.doFilter(request, response);
    }
}
