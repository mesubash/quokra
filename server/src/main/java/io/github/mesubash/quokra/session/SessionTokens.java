package io.github.mesubash.quokra.session;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.mesubash.quokra.conversation.CustomerIdentity;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Signs and checks the chat session tokens held by the chat panel.
@Slf4j
@Component
public class SessionTokens {

    // customer: set once the host has identified a logged-in customer; null for guests.
    public record Session(UUID visitorId, UUID tenantId, CustomerIdentity customer, Instant expiresAt) {

        public String customerSub() {
            return customer == null ? null : customer.sub();
        }
    }

    public record Issued(String token, Session session) {}

    private static final Duration TTL = Duration.ofDays(30);
    private static final int MIN_KEY_BYTES = 32;

    private final byte[] key;
    private final Clock clock;

    public SessionTokens(@Value("${quokra.session.signing-key:}") String signingKey, Clock clock) {
        this.key = keyOrRandom(signingKey);
        this.clock = clock;
    }

    public Issued issue(UUID tenantId) {
        return issue(tenantId, UUID.randomUUID(), null);
    }

    public Issued issue(UUID tenantId, UUID visitorId, CustomerIdentity customer) {
        Instant now = clock.instant();
        Session session = new Session(visitorId, tenantId, customer, now.plus(TTL));
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
            .subject(visitorId.toString())
            .claim("tid", tenantId.toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(session.expiresAt()));
        if (customer != null) {
            builder.claim("cus", customer.sub()).claim("cnm", customer.name()).claim("cem", customer.email());
        }
        JWTClaimsSet claims = builder.build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign session token", e);
        }
        return new Issued(jwt.serialize(), session);
    }

    public Optional<Session> verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(new MACVerifier(key))) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Instant expiresAt = claims.getExpirationTime().toInstant();
            if (!expiresAt.isAfter(clock.instant())) {
                return Optional.empty();
            }
            String customerSub = claims.getStringClaim("cus");
            CustomerIdentity customer = customerSub == null ? null
                : new CustomerIdentity(customerSub, claims.getStringClaim("cnm"), claims.getStringClaim("cem"));
            return Optional.of(new Session(
                UUID.fromString(claims.getSubject()),
                UUID.fromString(claims.getStringClaim("tid")),
                customer,
                expiresAt));
        } catch (ParseException | JOSEException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static byte[] keyOrRandom(String configured) {
        if (configured != null && !configured.isBlank()) {
            byte[] bytes = configured.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < MIN_KEY_BYTES) {
                throw new IllegalStateException("quokra.session.signing-key must be at least 32 bytes");
            }
            return bytes;
        }
        // Fine for local work and tests; sessions won't survive a restart.
        log.warn("quokra.session.signing-key is not set, using a random key");
        byte[] random = new byte[MIN_KEY_BYTES];
        new SecureRandom().nextBytes(random);
        return random;
    }
}
