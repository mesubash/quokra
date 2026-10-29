package io.github.mesubash.quokra.security;

import io.github.mesubash.quokra.session.SessionTokens;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SessionTokens sessionTokens) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // The chat panel and console live in iframes on other sites.
            // Per-tenant frame-ancestors replaces this later (step 6).
            .headers(headers -> headers.frameOptions(frame -> frame.disable()))
            .addFilterBefore(new SessionAuthFilter(sessionTokens), AuthorizationFilter.class)
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, e) ->
                    writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "Missing or invalid token"))
                .accessDeniedHandler((request, response, e) ->
                    writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Not allowed")))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**").permitAll()
                // The socket checks its own token on CONNECT.
                .requestMatchers("/ws", "/ws/**").permitAll()
                .requestMatchers("/", "/index.html", "/sdk.js", "/assets/**", "/w/**", "/console", "/console/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/widget/tenants/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/widget/sessions").permitAll()
                .requestMatchers(SessionAuthFilter.PATH, SessionAuthFilter.PATH + "/**").hasRole(SessionAuthFilter.ROLE)
                .anyRequest().denyAll());
        return http.build();
    }

    private static void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
            "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"details\":null}}");
    }
}
