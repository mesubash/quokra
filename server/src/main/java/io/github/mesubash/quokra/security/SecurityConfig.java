package io.github.mesubash.quokra.security;

import io.github.mesubash.quokra.session.SessionTokens;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Slf4j
@Configuration
public class SecurityConfig {

    static final AuthenticationEntryPoint UNAUTHORIZED = (request, response, e) ->
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "Missing or invalid token");

    static final AccessDeniedHandler FORBIDDEN = (request, response, e) ->
        writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Not allowed");

    // Agent endpoints: OIDC access tokens with the agent role.
    @Bean
    @Order(1)
    SecurityFilterChain agentChain(HttpSecurity http, ObjectProvider<JwtDecoder> decoder, AgentJwtConverter converter)
            throws Exception {
        common(http.securityMatcher("/api/v1/agent/**"));
        JwtDecoder jwtDecoder = decoder.getIfAvailable();
        if (jwtDecoder != null) {
            http.oauth2ResourceServer(oauth -> oauth
                .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(converter))
                .authenticationEntryPoint(UNAUTHORIZED)
                .accessDeniedHandler(FORBIDDEN));
        } else {
            log.warn("No agent login configured (set OIDC_ISSUER_URI): agent endpoints will refuse every request");
        }
        http.authorizeHttpRequests(auth -> auth.anyRequest().hasRole(AgentJwtConverter.AGENT_ROLE));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http, SessionTokens sessionTokens) throws Exception {
        common(http)
            .addFilterBefore(new SessionAuthFilter(sessionTokens), AuthorizationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**").permitAll()
                // The socket checks its own token on CONNECT.
                .requestMatchers("/ws", "/ws/**").permitAll()
                .requestMatchers("/", "/index.html", "/sdk.js", "/assets/**", "/w/**", "/console", "/console/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/widget/tenants/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/widget/sessions").permitAll()
                .requestMatchers(SessionAuthFilter.PATH, SessionAuthFilter.PATH + "/**").hasRole(SessionAuthFilter.ROLE)
                // Only exists when dev agent tokens are on.
                .requestMatchers(HttpMethod.POST, "/dev/agent-token").permitAll()
                .anyRequest().denyAll());
        return http.build();
    }

    private static HttpSecurity common(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // The chat panel and console live in iframes on other sites; frame-ancestors is set per page.
            .headers(headers -> headers.frameOptions(frame -> frame.disable()))
            .exceptionHandling(errors -> errors.authenticationEntryPoint(UNAUTHORIZED).accessDeniedHandler(FORBIDDEN));
    }

    private static void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
            "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"details\":null}}");
    }
}
