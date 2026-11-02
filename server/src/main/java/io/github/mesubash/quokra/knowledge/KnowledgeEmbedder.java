package io.github.mesubash.quokra.knowledge;

import io.github.mesubash.quokra.ai.AiClient;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Gives chunks their embeddings in the background: new and edited documents, and every chunk again after the
// embedding model changes. A failed batch is simply tried again on the next run.
@Slf4j
@Component
public class KnowledgeEmbedder {

    static final int BATCH = 50;

    private record Waiting(UUID documentId, int position, String heading, String body) {
        // The heading goes along, so "Returns" helps find a part that never says the word.
        String text() {
            return heading == null ? body : heading + "\n\n" + body;
        }
    }

    private final JdbcTemplate jdbc;
    private final AiClient ai;

    public KnowledgeEmbedder(JdbcTemplate jdbc, AiClient ai) {
        this.jdbc = jdbc;
        this.ai = ai;
    }

    @Scheduled(fixedDelayString = "${quokra.ai.embed-every}", initialDelayString = "PT20S")
    public void run() {
        if (!ai.enabled()) {
            return;
        }
        try {
            while (embedBatch() == BATCH) {
                // more waiting
            }
        } catch (AiClient.AiException e) {
            log.warn("Embedding knowledge failed, trying again later: {}", e.getMessage());
        }
    }

    // Returns how many chunks it embedded.
    int embedBatch() {
        List<Waiting> waiting = jdbc.query("""
            select document_id, position, heading, body from knowledge_chunks
            where embedding is null or embedding_model is distinct from ?
            order by document_id, position limit ?
            """, (rs, i) -> new Waiting(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4)),
            ai.embeddingModel(), BATCH);
        if (waiting.isEmpty()) {
            return 0;
        }
        List<float[]> vectors = ai.embed(waiting.stream().map(Waiting::text).toList());
        for (int i = 0; i < waiting.size(); i++) {
            // A chunk replaced meanwhile (document edited) just isn't found; its successor waits for the next run.
            jdbc.update("""
                update knowledge_chunks set embedding = ?::vector, embedding_model = ?
                where document_id = ? and position = ? and body = ?
                """, KnowledgeSearch.vector(vectors.get(i)), ai.embeddingModel(),
                waiting.get(i).documentId(), waiting.get(i).position(), waiting.get(i).body());
        }
        return waiting.size();
    }
}
