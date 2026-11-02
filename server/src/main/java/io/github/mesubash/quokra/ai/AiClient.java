package io.github.mesubash.quokra.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Talks to an OpenAI-compatible API (Gemini's, OpenAI, Ollama, …). Failures are AiException, so callers can
// fall back to the bot and handoff; nothing here is ever shown to customers as is.
@Component
public class AiClient {

    public static class AiException extends RuntimeException {
        public AiException(String message) {
            super(message);
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http;
    private final String baseUrl;
    private final String key;
    private final String embeddingModel;
    private final int dimensions;
    private final Duration timeout;

    public AiClient(@Value("${quokra.ai.base-url}") String baseUrl,
                    @Value("${quokra.ai.key:}") String key,
                    @Value("${quokra.ai.embedding-model}") String embeddingModel,
                    @Value("${quokra.ai.dimensions}") int dimensions,
                    @Value("${quokra.ai.timeout}") Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.key = key.strip();
        this.embeddingModel = embeddingModel;
        this.dimensions = dimensions;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public boolean enabled() {
        return !key.isEmpty();
    }

    public String embeddingModel() {
        return embeddingModel;
    }

    // One vector per text, in the same order.
    public List<float[]> embed(List<String> texts) {
        JsonNode body = post("embeddings", Map.of("model", embeddingModel, "input", texts, "dimensions", dimensions));
        JsonNode data = body.path("data");
        if (data.size() != texts.size()) {
            throw new AiException("embeddings: expected " + texts.size() + " vectors, got " + data.size());
        }
        return data.valueStream().map(d -> {
            JsonNode values = d.path("embedding");
            if (values.size() != dimensions) {
                throw new AiException("embeddings: expected " + dimensions + " numbers, got " + values.size());
            }
            float[] vector = new float[dimensions];
            for (int i = 0; i < dimensions; i++) {
                vector[i] = values.get(i).floatValue();
            }
            return vector;
        }).toList();
    }

    private JsonNode post(String path, Object body) {
        if (!enabled()) {
            throw new AiException("AI is off: no QUOKRA_AI_KEY");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(timeout)
            .header("Authorization", "Bearer " + key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // Only the status: provider messages can echo what was sent.
                throw new AiException(path + ": HTTP " + response.statusCode());
            }
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new AiException(path + ": " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(path + ": interrupted");
        }
    }
}
