package io.github.mesubash.quokra.web;

import io.github.mesubash.quokra.tenant.TenantRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

// Client-side routes of the web UI all load index.html. Each one says which sites may embed it.
@Controller
public class SpaController {

    // Only plain origins go into the header, so a bad value in the database can't inject anything.
    private static final Pattern ORIGIN = Pattern.compile("https?://[A-Za-z0-9.-]+(:\\d{1,5})?");

    private final TenantRepository tenants;

    public SpaController(TenantRepository tenants) {
        this.tenants = tenants;
    }

    @GetMapping("/w/{slug}")
    public String chat(@PathVariable String slug, HttpServletResponse response) {
        List<String> origins = tenants.findBySlug(slug).map(t -> t.getWidgetOrigins()).orElse(List.of());
        response.setHeader("Content-Security-Policy", frameAncestors(origins));
        return "forward:/index.html";
    }

    @GetMapping("/console")
    public String console(HttpServletResponse response) {
        // Per-tenant console origins come with the console (step 8).
        response.setHeader("Content-Security-Policy", frameAncestors(List.of()));
        return "forward:/index.html";
    }

    static String frameAncestors(List<String> origins) {
        StringBuilder value = new StringBuilder("frame-ancestors 'self'");
        origins.stream().filter(o -> ORIGIN.matcher(o).matches()).forEach(o -> value.append(' ').append(o));
        return value.toString();
    }
}
