package io.github.mesubash.quokra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class AgentTokenConfigTests {

    static final String COMPANY = "https://login.example.test/realms/acme";
    static final String DEV = "quokra-dev";

    final KeyPair companyKeys = keys();
    final KeyPair devKeys = keys();
    final JwtDecoder decoder = AgentTokenConfig.byIssuer(Map.of(
        COMPANY, decoderFor(companyKeys, COMPANY),
        DEV, decoderFor(devKeys, DEV))::get);

    static KeyPair keys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static JwtDecoder decoderFor(KeyPair keys, String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
        decoder.setJwtValidator(OidcConfig.validator(issuer, "quokra-api"));
        return decoder;
    }

    static String token(KeyPair signedWith, String issuer, String audience) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject("agent-1")
            .expirationTime(Date.from(Instant.now().plusSeconds(300))).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(signedWith.getPrivate()));
        return jwt.serialize();
    }

    @Test
    void acceptsTokensFromEachKnownIssuer() throws Exception {
        assertThat(decoder.decode(token(companyKeys, COMPANY, "quokra-api")).getSubject()).isEqualTo("agent-1");
        assertThat(decoder.decode(token(devKeys, DEV, "quokra-api")).getSubject()).isEqualTo("agent-1");
    }

    @Test
    void refusesATokenThatNamesOneIssuerButIsSignedByAnother() throws Exception {
        String forged = token(devKeys, COMPANY, "quokra-api");

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void refusesUnknownIssuersWrongAudiencesAndGarbage() throws Exception {
        String unknown = token(companyKeys, "https://elsewhere.test", "quokra-api");
        String otherApp = token(companyKeys, COMPANY, "some-other-api");

        for (String token : List.of(unknown, otherApp, "not-a-token")) {
            assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
        }
    }

    @Test
    void displayNameUsesTheConfiguredClaimFirst() {
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t").header("alg", "RS256")
            .subject("agent-1").claim("full_name", "Sam Lee").claim("name", "sam").build();

        assertThat(new AgentJwtConverter("roles", "SUPPORT_AGENT", "full_name").displayName(jwt)).isEqualTo("Sam Lee");
        assertThat(new AgentJwtConverter("roles", "SUPPORT_AGENT", "").displayName(jwt)).isEqualTo("sam");
    }
}
