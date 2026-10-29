package io.github.mesubash.quokra.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionTokensTests {

    static final String KEY = "a-test-signing-key-that-is-long-enough";
    static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");
    static final UUID TENANT = UUID.randomUUID();

    SessionTokens tokens = new SessionTokens(KEY, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void issuedTokenVerifies() {
        SessionTokens.Issued issued = tokens.issue(TENANT);

        assertThat(tokens.verify(issued.token())).contains(issued.session());
        assertThat(issued.session().tenantId()).isEqualTo(TENANT);
        assertThat(issued.session().expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        SessionTokens other = new SessionTokens("another-signing-key-that-is-long-enough", Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(tokens.verify(other.issue(TENANT).token())).isEmpty();
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = tokens.issue(TENANT).token();
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");

        assertThat(tokens.verify(tampered)).isEmpty();
    }

    @Test
    void expiredTokenIsRejected() {
        String token = tokens.issue(TENANT).token();
        SessionTokens later = new SessionTokens(KEY, Clock.fixed(NOW.plus(Duration.ofDays(31)), ZoneOffset.UTC));

        assertThat(later.verify(token)).isEmpty();
    }

    @Test
    void garbageIsRejected() {
        assertThat(tokens.verify("not-a-token")).isEmpty();
    }

    @Test
    void shortKeyIsRefused() {
        assertThatThrownBy(() -> new SessionTokens("short", Clock.systemUTC()))
            .isInstanceOf(IllegalStateException.class);
    }
}
