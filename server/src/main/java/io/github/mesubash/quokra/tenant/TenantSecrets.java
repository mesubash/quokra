package io.github.mesubash.quokra.tenant;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// Tenant secret keys, stored encrypted with AES-GCM. Stored value: 12-byte nonce, then ciphertext + tag.
// Without TENANT_SECRET_ENCRYPTION_KEY, identify is off: no secret can be read or created.
@Slf4j
@Component
public class TenantSecrets {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;
    private final JdbcTemplate jdbc;

    public TenantSecrets(@Value("${quokra.tenant-secrets.encryption-key:}") String encryptionKey, JdbcTemplate jdbc) {
        this.key = keyFrom(encryptionKey);
        this.jdbc = jdbc;
    }

    public boolean enabled() {
        return key != null;
    }

    public Optional<String> secretFor(UUID tenantId) {
        if (key == null) {
            return Optional.empty();
        }
        return jdbc.queryForList("select secret_key_enc from tenants where id = ?", byte[].class, tenantId).stream()
            .filter(bytes -> bytes != null)
            .findFirst()
            .map(this::decrypt);
    }

    // Makes a new secret for the tenant, replacing the old one. Returns it; it's never stored in plain text.
    public String rotate(String slug) {
        if (key == null) {
            throw new IllegalStateException("Set TENANT_SECRET_ENCRYPTION_KEY first");
        }
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String secret = "sk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        int updated = jdbc.update("update tenants set secret_key_enc = ? where slug = ?", encrypt(secret), slug);
        if (updated == 0) {
            throw new IllegalArgumentException("No tenant with slug " + slug);
        }
        return secret;
    }

    byte[] encrypt(String secret) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt the tenant secret", e);
        }
    }

    String decrypt(byte[] stored) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 0, NONCE_BYTES));
            return new String(cipher.doFinal(stored, NONCE_BYTES, stored.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Wrong encryption key, or the value was changed in the database.
            throw new IllegalStateException("Could not decrypt the tenant secret", e);
        }
    }

    private static SecretKeySpec keyFrom(String base64) {
        if (base64 == null || base64.isBlank()) {
            log.warn("TENANT_SECRET_ENCRYPTION_KEY is not set: identifying logged-in customers is turned off");
            return null;
        }
        byte[] bytes = Base64.getDecoder().decode(base64.strip());
        if (bytes.length != 32) {
            throw new IllegalStateException("TENANT_SECRET_ENCRYPTION_KEY must be 32 bytes, base64 encoded");
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
