package io.github.mesubash.quokra.knowledge;

import io.github.mesubash.quokra.common.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// A company's knowledge documents (v3), managed in Settings. Saving one splits it into chunks again.
@Service
public class KnowledgeDocuments {

    public static final int MAX_TITLE = 120;
    public static final int MAX_BODY = 200_000;
    public static final int MAX_DOCUMENTS = 200;

    // chunks: how many parts it was split into.
    public record Summary(UUID id, String title, int length, int chunks, String updatedBy, Instant updatedAt) {}

    public record Document(UUID id, String title, String body, String updatedBy, Instant updatedAt) {}

    private final JdbcTemplate jdbc;

    public KnowledgeDocuments(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Summary> list(UUID tenantId) {
        return jdbc.query("""
            select d.id, d.title, length(d.body), (select count(*) from knowledge_chunks c where c.document_id = d.id),
                   d.updated_by, d.updated_at
            from knowledge_documents d where d.tenant_id = ? order by lower(d.title)
            """, (rs, i) -> new Summary(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getInt(4),
                rs.getString(5), rs.getTimestamp(6).toInstant()), tenantId);
    }

    @Transactional(readOnly = true)
    public Optional<Document> get(UUID tenantId, UUID id) {
        return jdbc.query("""
            select id, title, body, updated_by, updated_at from knowledge_documents where tenant_id = ? and id = ?
            """, (rs, i) -> new Document(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getTimestamp(5).toInstant()), tenantId, id).stream().findFirst();
    }

    @Transactional
    public Document add(UUID tenantId, String title, String body, String by) {
        // Locks the tenant row so two uploads at once can't both slip under the limit.
        jdbc.queryForObject("select id from tenants where id = ? for update", UUID.class, tenantId);
        Integer count = jdbc.queryForObject("select count(*) from knowledge_documents where tenant_id = ?",
            Integer.class, tenantId);
        if (count != null && count >= MAX_DOCUMENTS) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_DOCUMENTS",
                "A company can have up to " + MAX_DOCUMENTS + " documents");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("insert into knowledge_documents (id, tenant_id, title, body, updated_by) values (?, ?, ?, ?, ?)",
            id, tenantId, title, body, by);
        chunk(id, body);
        return get(tenantId, id).orElseThrow();
    }

    @Transactional
    public Optional<Document> update(UUID tenantId, UUID id, String title, String body, String by) {
        int changed = jdbc.update("""
            update knowledge_documents set title = ?, body = ?, updated_by = ?, updated_at = now()
            where tenant_id = ? and id = ?
            """, title, body, by, tenantId, id);
        if (changed == 0) {
            return Optional.empty();
        }
        jdbc.update("delete from knowledge_chunks where document_id = ?", id);
        chunk(id, body);
        return get(tenantId, id);
    }

    public boolean remove(UUID tenantId, UUID id) {
        return jdbc.update("delete from knowledge_documents where tenant_id = ? and id = ?", tenantId, id) > 0;
    }

    private void chunk(UUID documentId, String body) {
        List<KnowledgeChunker.Chunk> chunks = KnowledgeChunker.split(body);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            rows.add(new Object[] {documentId, i, chunks.get(i).heading(), chunks.get(i).body()});
        }
        jdbc.batchUpdate("insert into knowledge_chunks (document_id, position, heading, body) values (?, ?, ?, ?)", rows);
    }
}
