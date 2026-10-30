package io.github.mesubash.quokra.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.mesubash.quokra.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import org.junit.jupiter.api.Test;

class IdentityTokensTests {

    static final String SECRET = "sk_test_0123456789abcdef0123456789abcdef";
    static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    final IdentityTokens tokens = new IdentityTokens(Clock.fixed(NOW, ZoneOffset.UTC));

    static String sign(String secret, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    static JWTClaimsSet.Builder claims(Instant issued, Duration lifetime) {
        return new JWTClaimsSet.Builder().subject("user-123").claim("name", "Pat Doe").claim("email", "pat@example.test")
            .issueTime(Date.from(issued)).expirationTime(Date.from(issued.plus(lifetime)));
    }

    String code(ThrowingRunnable r) {
        try {
            r.run();
        } catch (ApiException e) {
            return e.code();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("Expected an ApiException");
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void acceptsAValidToken() throws Exception {
        var customer = tokens.verify(sign(SECRET, claims(NOW, Duration.ofMinutes(5)).build()), SECRET);

        assertThat(customer.sub()).isEqualTo("user-123");
        assertThat(customer.name()).isEqualTo("Pat Doe");
        assertThat(customer.email()).isEqualTo("pat@example.test");
    }

    @Test
    void rejectsAnExpiredToken() throws Exception {
        String token = sign(SECRET, claims(NOW.minus(Duration.ofMinutes(10)), Duration.ofMinutes(5)).build());

        assertThat(code(() -> tokens.verify(token, SECRET))).isEqualTo("IDENTITY_TOKEN_EXPIRED");
    }

    @Test
    void rejectsATokenThatLivesTooLong() throws Exception {
        String token = sign(SECRET, claims(NOW, Duration.ofMinutes(6)).build());

        assertThat(code(() -> tokens.verify(token, SECRET))).isEqualTo("IDENTITY_TOKEN_INVALID");
    }

    @Test
    void rejectsATokenSignedForAnotherTenant() throws Exception {
        String token = sign("sk_other_tenant_0123456789abcdef0123456789", claims(NOW, Duration.ofMinutes(5)).build());

        assertThat(code(() -> tokens.verify(token, SECRET))).isEqualTo("IDENTITY_TOKEN_INVALID");
    }

    @Test
    void rejectsATamperedToken() throws Exception {
        String[] parts = sign(SECRET, claims(NOW, Duration.ofMinutes(5)).build()).split("\\.");
        String otherPayload = sign(SECRET, claims(NOW, Duration.ofMinutes(5)).subject("admin").build()).split("\\.")[1];
        String tampered = parts[0] + "." + otherPayload + "." + parts[2];

        assertThat(code(() -> tokens.verify(tampered, SECRET))).isEqualTo("IDENTITY_TOKEN_INVALID");
    }

    @Test
    void rejectsUnsignedAndMalformedTokens() {
        String unsigned = new com.nimbusds.jwt.PlainJWT(claims(NOW, Duration.ofMinutes(5)).build()).serialize();

        assertThat(code(() -> tokens.verify(unsigned, SECRET))).isEqualTo("IDENTITY_TOKEN_INVALID");
        assertThat(code(() -> tokens.verify("not-a-token", SECRET))).isEqualTo("IDENTITY_TOKEN_INVALID");
    }

    @Test
    void requiresASubject() throws Exception {
        String token = sign(SECRET, claims(NOW, Duration.ofMinutes(5)).subject(null).build());

        assertThatThrownBy(() -> tokens.verify(token, SECRET)).isInstanceOf(ApiException.class);
    }
}
