package io.github.mesubash.quokra.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RetentionLimitsTests {

    @Test
    void withAMaximumForeverIsNotAllowed() {
        var limits = new RetentionLimits(3, 24);
        assertThat(limits.allows(12)).isTrue();
        assertThat(limits.allows(2)).isFalse();
        assertThat(limits.allows(36)).isFalse();
        assertThat(limits.allows(0)).isFalse();
        // Choices made before the limits changed are brought inside them.
        assertThat(limits.effective(0)).isEqualTo(24);
        assertThat(limits.effective(60)).isEqualTo(24);
        assertThat(limits.effective(1)).isEqualTo(3);
    }

    @Test
    void maxZeroAllowsForever() {
        var limits = new RetentionLimits(1, 0);
        assertThat(limits.allows(0)).isTrue();
        assertThat(limits.allows(120)).isTrue();
        assertThat(limits.effective(0)).isZero();
    }

    @Test
    void missingValuesGetDefaults() {
        var limits = new RetentionLimits(null, null);
        assertThat(limits.minMonths()).isEqualTo(1);
        assertThat(limits.maxMonths()).isEqualTo(60);
    }
}
