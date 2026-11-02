package io.github.mesubash.quokra.ai;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// A stand-in for an OpenAI-compatible API in tests. Embeddings come from the words in the text (each word
// adds to a few of the 768 numbers), so texts sharing words are close and search can be checked.
public final class FakeAi {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final int DIMENSIONS = 768;

    public final AtomicInteger requests = new AtomicInteger();
    public final AtomicInteger chats = new AtomicInteger();
    // What /chat/completions answers with (the model's text), and the last request it got.
    public volatile String chatReply = "{\"answer\": \"NONE\", \"sources\": []}";
    public volatile String lastChat;
    // HTTP status to answer with instead of 200, e.g. 429 or 500; 0 for normal answers.
    public volatile int failWith;
    // How many of the next requests fail with failNextWith before answering normally again.
    public final AtomicInteger failNext = new AtomicInteger();
    public volatile int failNextWith = 503;

    private final HttpServer server;

    public FakeAi() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/embeddings", exchange -> {
            requests.incrementAndGet();
            JsonNode body = JSON.readTree(exchange.getRequestBody());
            byte[] reply;
            int status = status();
            if (status == 200) {
                List<Object> data = new ArrayList<>();
                for (JsonNode text : body.path("input")) {
                    data.add(java.util.Map.of("embedding", embed(text.asString())));
                }
                reply = JSON.writeValueAsBytes(java.util.Map.of("data", data));
            } else {
                reply = "{\"error\":{\"message\":\"nope\"}}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.createContext("/chat/completions", exchange -> {
            chats.incrementAndGet();
            lastChat = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = status();
            byte[] reply = status == 200
                ? JSON.writeValueAsBytes(java.util.Map.of("choices",
                    List.of(java.util.Map.of("message", java.util.Map.of("role", "assistant", "content", chatReply)))))
                : "{\"error\":{\"message\":\"nope\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
    }

    private int status() {
        if (failNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            return failNextWith;
        }
        return failWith == 0 ? 200 : failWith;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    static float[] embed(String text) {
        float[] v = new float[DIMENSIONS];
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (word.length() < 3) {
                continue;
            }
            int h = word.hashCode();
            for (int k = 0; k < 3; k++) {
                v[Math.floorMod(h * (31 + k), DIMENSIONS)] += 1;
            }
        }
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        if (norm == 0) {
            v[0] = 1;
            return v;
        }
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) (v[i] / Math.sqrt(norm));
        }
        return v;
    }
}
