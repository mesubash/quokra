package io.github.mesubash.quokra.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class TenantSecretsTests {

    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptsAndDecryptsWithAFreshNonceEachTime() {
        TenantSecrets secrets = new TenantSecrets(KEY, null);

        byte[] first = secrets.encrypt("sk_live_secret");
        byte[] second = secrets.encrypt("sk_live_secret");

        assertThat(secrets.decrypt(first)).isEqualTo("sk_live_secret");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void detectsAChangedValue() {
        TenantSecrets secrets = new TenantSecrets(KEY, null);
        byte[] stored = secrets.encrypt("sk_live_secret");
        stored[stored.length - 1] ^= 1;

        assertThatThrownBy(() -> secrets.decrypt(stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void isOffWithoutAKeyAndRejectsAShortOne() {
        assertThat(new TenantSecrets("", null).enabled()).isFalse();
        assertThatThrownBy(() -> new TenantSecrets(Base64.getEncoder().encodeToString(new byte[16]), null))
            .isInstanceOf(IllegalStateException.class);
    }
}
