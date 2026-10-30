package io.github.mesubash.quokra.session;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.mesubash.quokra.common.ApiException;
import io.github.mesubash.quokra.conversation.CustomerIdentity;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

// Checks identity tokens signed by a tenant's backend (HS256 with the tenant secret key, at most 5 minutes).
@Component
public class IdentityTokens {

    static final Duration MAX_LIFETIME = Duration.ofMinutes(5);
    // Small allowance for clocks that are a little apart.
    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    static final int MAX_FIELD_LENGTH = 254;

    private final Clock clock;

    public IdentityTokens(Clock clock) {
        this.clock = clock;
    }

    public CustomerIdentity verify(String token, String tenantSecret) {
        JWTClaimsSet claims;
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())
                    || !jwt.verify(new MACVerifier(tenantSecret.getBytes(StandardCharsets.UTF_8)))) {
                throw invalid();
            }
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException | JOSEException e) {
            throw invalid();
        }

        Date issued = claims.getIssueTime();
        Date expires = claims.getExpirationTime();
        String sub = claims.getSubject();
        if (issued == null || expires == null || sub == null || sub.isBlank() || sub.length() > MAX_FIELD_LENGTH
                || Duration.between(issued.toInstant(), expires.toInstant()).compareTo(MAX_LIFETIME) > 0) {
            throw invalid();
        }
        Instant now = clock.instant();
        if (!expires.toInstant().plus(CLOCK_SKEW).isAfter(now)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "IDENTITY_TOKEN_EXPIRED", "The identity token has expired");
        }
        if (issued.toInstant().minus(CLOCK_SKEW).isAfter(now)) {
            throw invalid();
        }
        return new CustomerIdentity(sub, text(claims, "name"), text(claims, "email"));
    }

    private static String text(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        if (!(value instanceof String s) || s.isBlank()) {
            return null;
        }
        return s.length() > MAX_FIELD_LENGTH ? s.substring(0, MAX_FIELD_LENGTH) : s.strip();
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "IDENTITY_TOKEN_INVALID", "The identity token isn't valid");
    }
}
