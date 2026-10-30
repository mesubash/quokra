package io.github.mesubash.quokra.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// Local agent login for development and tests: the server signs agent tokens itself.
// Turned on by quokra.dev.agent-tokens=true (the dev profile). Never in production.
@Slf4j
@Configuration
@ConditionalOnProperty(name = "quokra.dev.agent-tokens", havingValue = "true")
@RestController
public class DevAgentTokens {

    static final String ISSUER = "quokra-dev";

    public record TokenRequest(@NotBlank String sub, String name) {}

    public record TokenResponse(String accessToken) {}

    private final KeyPair keys;
    private final String audience;
    private final List<String> roles;
    private final String rolesClaim;
    private final Clock clock;

    public DevAgentTokens(@Value("${APP_ENV:}") String appEnv,
                          @Value("${quokra.agents.audience}") String audience,
                          @Value("${quokra.agents.role}") String role,
                          @Value("${quokra.agents.roles-claim}") String rolesClaim,
                          Clock clock) throws NoSuchAlgorithmException {
        if ("production".equalsIgnoreCase(appEnv)) {
            throw new IllegalStateException("quokra.dev.agent-tokens must not be enabled when APP_ENV=production");
        }
        log.warn("Dev agent tokens are enabled: anyone who can reach POST /dev/agent-token can act as an agent");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        this.keys = generator.generateKeyPair();
        this.audience = audience;
        this.roles = List.of(role);
        this.rolesClaim = rolesClaim;
        this.clock = clock;
    }

    @Bean
    AgentTokenIssuer devIssuer() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
        decoder.setJwtValidator(OidcConfig.validator(ISSUER, audience));
        return new AgentTokenIssuer(ISSUER, decoder);
    }

    // A local dashboard on another port (like the test host) can sign in too.
    @CrossOrigin(originPatterns = {"http://localhost:[*]", "http://127.0.0.1:[*]"})
    @PostMapping("/dev/agent-token")
    public TokenResponse token(@Valid @RequestBody TokenRequest request) throws JOSEException {
        return new TokenResponse(sign(request.sub(), request.name() == null ? request.sub() : request.name(), roles));
    }

    // Also used by tests that need tokens with other roles.
    public String sign(String sub, String name, List<String> tokenRoles) throws JOSEException {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience(audience)
            .subject(sub)
            .claim("name", name)
            .claim(rolesClaim, tokenRoles)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(Duration.ofHours(8))))
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(keys.getPrivate()));
        return jwt.serialize();
    }
}
