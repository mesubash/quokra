package io.github.mesubash.quokra.canned;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

// Saved answers per tenant, managed in the Admin tab and picked by agents while replying.
@Component
public class CannedReplies {

    public static final int MAX_TITLE = 80;

    public record Reply(UUID id, String title, String body) {}

    private static final RowMapper<Reply> ROW = (rs, i) ->
        new Reply(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3));

    private final JdbcTemplate jdbc;

    public CannedReplies(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Reply> list(UUID tenantId) {
        return jdbc.query("select id, title, body from canned_replies where tenant_id = ? order by lower(title)",
            ROW, tenantId);
    }

    public Reply add(UUID tenantId, String title, String body) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into canned_replies (id, tenant_id, title, body) values (?, ?, ?, ?)", id, tenantId, title, body);
        return new Reply(id, title, body);
    }

    public boolean update(UUID tenantId, UUID id, String title, String body) {
        return jdbc.update("update canned_replies set title = ?, body = ? where tenant_id = ? and id = ?",
            title, body, tenantId, id) > 0;
    }

    public boolean remove(UUID tenantId, UUID id) {
        return jdbc.update("delete from canned_replies where tenant_id = ? and id = ?", tenantId, id) > 0;
    }
}
