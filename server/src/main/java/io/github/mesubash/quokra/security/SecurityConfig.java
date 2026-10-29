package io.github.mesubash.quokra.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // The chat panel and console live in iframes on other sites.
            // Per-tenant frame-ancestors replaces this later (step 6).
            .headers(headers -> headers.frameOptions(frame -> frame.disable()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers("/", "/index.html", "/sdk.js", "/assets/**", "/w/**", "/console", "/console/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/widget/tenants/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/widget/sessions").permitAll()
                .anyRequest().denyAll());
        return http.build();
    }
}
