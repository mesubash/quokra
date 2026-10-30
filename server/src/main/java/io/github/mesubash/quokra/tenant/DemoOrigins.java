package io.github.mesubash.quokra.tenant;

import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// On a public demo, the demo company must allow the playground's site before anyone can open its console
// to change that, so quokra.demo.origins is added to its websites and dashboards on start.
@Slf4j
@Component
@Profile("public-demo")
class DemoOrigins {

    private final JdbcTemplate jdbc;
    private final String origins;

    DemoOrigins(JdbcTemplate jdbc, @Value("${quokra.demo.origins:}") String origins) {
        this.jdbc = jdbc;
        this.origins = origins;
    }

    @EventListener(ApplicationReadyEvent.class)
    void allow() {
        String[] list = Arrays.stream(origins.split(",")).map(s -> s.strip().replaceAll("/+$", "").toLowerCase())
            .filter(s -> !s.isEmpty()).toArray(String[]::new);
        if (list.length == 0) {
            return;
        }
        var array = jdbc.execute((ConnectionCallback<java.sql.Array>) c -> c.createArrayOf("text", list));
        int updated = jdbc.update("""
            update tenants set
              widget_origins = array(select distinct unnest(widget_origins || ?::text[])),
              console_origins = array(select distinct unnest(console_origins || ?::text[]))
            where slug = 'demo'
            """, array, array);
        log.info("Demo company allows {} ({} updated)", String.join(", ", list), updated);
    }
}
