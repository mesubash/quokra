package io.github.mesubash.quokra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiClientTests {

    static final FakeAi AI = new FakeAi();

    final AiClient client = new AiClient(true, AI.baseUrl(), "test-key", "chat-model", "none", "embedding-model", 768,
        Duration.ofSeconds(5));

    @Test
    void aBusyProviderIsTriedOnceMore() {
        AI.failNext.set(1);
        assertThat(client.embed(List.of("hello there"))).hasSize(1);
        AI.failNext.set(1);
        assertThat(client.chat("system", "user")).contains("NONE");
    }

    @Test
    void notMoreThanOnce() {
        AI.failNext.set(2);
        assertThatThrownBy(() -> client.embed(List.of("hello there"))).hasMessage("embeddings: HTTP 503");
        AI.failNext.set(0);
    }

    @Test
    void otherErrorsAreNotRetried() {
        AI.failNextWith = 401;
        AI.failNext.set(1);
        int before = AI.requests.get();
        assertThatThrownBy(() -> client.embed(List.of("hello there"))).hasMessage("embeddings: HTTP 401");
        assertThat(AI.requests.get()).isEqualTo(before + 1);
        AI.failNextWith = 503;
    }

    @Test
    void offWithoutAKey() {
        AiClient off = new AiClient(true, AI.baseUrl(), " ", "chat-model", "", "embedding-model", 768, Duration.ofSeconds(5));
        assertThat(off.enabled()).isFalse();
        assertThatThrownBy(() -> off.embed(List.of("x"))).hasMessageContaining("AI is off");
    }

    @Test
    void switchedOffForTheServerEvenWithAKey() {
        AiClient off = new AiClient(false, AI.baseUrl(), "test-key", "chat-model", "", "embedding-model", 768,
            Duration.ofSeconds(5));
        assertThat(off.enabled()).isFalse();
    }
}
