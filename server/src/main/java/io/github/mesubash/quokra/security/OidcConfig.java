package io.github.mesubash.quokra.security;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;

// Agent tokens from a real identity provider (any OIDC provider), when OIDC_ISSUER_URI is set.
// The provider's signing keys come from its discovery document, or from OIDC_JWKS_URI if it has none.
// They're fetched on first use, so the server starts even while the provider is still starting.
@Configuration
@ConditionalOnExpression("!'${quokra.agents.issuer-uri:}'.isBlank()")
class OidcConfig {

    @Bean
    AgentTokenIssuer oidcIssuer(@Value("${quokra.agents.issuer-uri}") String issuer,
                                @Value("${quokra.agents.jwks-uri:}") String jwksUri,
                                @Value("${quokra.agents.audience}") String audience) {
        SupplierJwtDecoder decoder = new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder nimbus = jwksUri.isBlank()
                ? NimbusJwtDecoder.withIssuerLocation(issuer).build()
                : NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
            nimbus.setJwtValidator(validator(issuer, audience));
            return nimbus;
        });
        return new AgentTokenIssuer(issuer, decoder);
    }

    static DelegatingOAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        return new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),
            new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(audience)));
    }
}
