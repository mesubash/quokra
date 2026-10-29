package io.github.mesubash.quokra.security;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

// Agent tokens from a real identity provider, when OIDC_ISSUER_URI is set.
@Configuration
@ConditionalOnExpression("!'${quokra.agents.issuer-uri:}'.isBlank()")
class OidcConfig {

    @Bean
    JwtDecoder agentJwtDecoder(@Value("${quokra.agents.issuer-uri}") String issuer,
                               @Value("${quokra.agents.audience}") String audience) {
        NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
        decoder.setJwtValidator(validator(issuer, audience));
        return decoder;
    }

    static DelegatingOAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        return new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),
            new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(audience)));
    }
}
