package io.github.mesubash.quokra.knowledge;

import io.github.mesubash.quokra.ai.AiClient;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

// Finds the parts of a company's documents closest to a question. Only parts at least min-score close count,
// so a question nothing in the documents is about finds nothing (and the AI isn't asked at all).
@Service
public class KnowledgeSearch {

    public static final int TOP = 5;

    // score: cosine similarity, 1 is the same meaning.
    public record Match(UUID documentId, String title, int position, String heading, String body, double score) {}

    private final JdbcTemplate jdbc;
    private final AiClient ai;
    private final double minScore;

    public KnowledgeSearch(JdbcTemplate jdbc, AiClient ai, @Value("${quokra.ai.min-score}") double minScore) {
        this.jdbc = jdbc;
        this.ai = ai;
        this.minScore = minScore;
    }

    public List<Match> search(UUID tenantId, String question) {
        return search(tenantId, question, minScore);
    }

    public List<Match> search(UUID tenantId, String question, double atLeast) {
        String q = vector(ai.embed(List.of(question)).getFirst());
        return jdbc.query("""
            select d.id, d.title, c.position, c.heading, c.body, 1 - (c.embedding <=> ?::vector) as score
            from knowledge_chunks c join knowledge_documents d on d.id = c.document_id
            where d.tenant_id = ? and c.embedding is not null and c.embedding_model = ?
              and 1 - (c.embedding <=> ?::vector) >= ?
            order by c.embedding <=> ?::vector
            limit ?
            """, (rs, i) -> new Match(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getString(4),
                rs.getString(5), rs.getDouble(6)),
            q, tenantId, ai.embeddingModel(), q, atLeast, q, TOP);
    }

    // pgvector's text form: [0.1,0.2,…]
    static String vector(float[] values) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            text.append(i == 0 ? "" : ",").append(values[i]);
        }
        return text.append(']').toString();
    }
}
