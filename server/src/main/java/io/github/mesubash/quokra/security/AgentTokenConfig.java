package io.github.mesubash.quokra.security;

import com.nimbusds.jwt.JWTParser;
import java.text.ParseException;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

// The decoder for agent tokens. Picks the issuer from the token's "iss" claim, then lets that issuer's own
// decoder do every real check, so a token can only pass with a valid signature from the issuer it names.
@Slf4j
@Configuration
class AgentTokenConfig {

    @Bean
    JwtDecoder agentJwtDecoder(ObjectProvider<AgentTokenIssuer> issuers) {
        Map<String, JwtDecoder> byIssuer = issuers.orderedStream()
            .collect(Collectors.toMap(AgentTokenIssuer::issuer, AgentTokenIssuer::decoder));
        if (byIssuer.isEmpty()) {
            log.warn("No agent login configured (set OIDC_ISSUER_URI): agent endpoints will refuse every request");
        } else {
            log.info("Agent tokens accepted from: {}", byIssuer.keySet());
        }
        return byIssuer(byIssuer::get);
    }

    static JwtDecoder byIssuer(Function<String, JwtDecoder> decoders) {
        return token -> {
            String issuer;
            try {
                issuer = JWTParser.parse(token).getJWTClaimsSet().getIssuer();
            } catch (ParseException e) {
                throw new BadJwtException("Not a valid token", e);
            }
            JwtDecoder decoder = issuer == null ? null : decoders.apply(issuer);
            if (decoder == null) {
                throw new BadJwtException("Tokens from this issuer aren't accepted");
            }
            return decoder.decode(token);
        };
    }
}
