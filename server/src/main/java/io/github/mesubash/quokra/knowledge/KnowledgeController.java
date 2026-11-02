package io.github.mesubash.quokra.knowledge;

import io.github.mesubash.quokra.ai.AiClient;
import io.github.mesubash.quokra.common.ApiException;
import io.github.mesubash.quokra.tenant.Tenant;
import io.github.mesubash.quokra.tenant.TenantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Knowledge documents in Settings. Under /admin/tenants/{slug}, so only that company's admins (and server
// admins) get here; see TenantAccess.
@RestController
@RequestMapping("/api/v1/admin/tenants/{slug}/knowledge")
public class KnowledgeController {

    record Items<T>(List<T> items) {}

    record DocumentForm(@NotBlank @Size(max = KnowledgeDocuments.MAX_TITLE) String title,
                        @NotBlank @Size(max = KnowledgeDocuments.MAX_BODY) String body) {}

    // match: close enough to count (quokra.ai.min-score); the rest are shown to help pick that score.
    record SearchResult(String title, String heading, String body, double score, boolean match) {}

    private final KnowledgeDocuments documents;
    private final TenantRepository tenants;
    private final KnowledgeSearch search;
    private final AiClient ai;
    private final double minScore;

    public KnowledgeController(KnowledgeDocuments documents, TenantRepository tenants, KnowledgeSearch search,
                               AiClient ai, @Value("${quokra.ai.min-score}") double minScore) {
        this.documents = documents;
        this.tenants = tenants;
        this.search = search;
        this.ai = ai;
        this.minScore = minScore;
    }

    // Tries a question against the documents, for checking what the AI would get to answer from.
    @GetMapping("/search")
    public Items<SearchResult> search(@PathVariable String slug, @RequestParam @NotBlank @Size(max = 500) String q) {
        if (!ai.enabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "AI_OFF", "AI is off on this server (no QUOKRA_AI_KEY)");
        }
        try {
            return new Items<>(search.search(tenant(slug), q.strip(), -1).stream()
                .map(m -> new SearchResult(m.title(), m.heading(), m.body(), m.score(), m.score() >= minScore))
                .toList());
        } catch (AiClient.AiException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "The AI provider didn't answer; try again");
        }
    }

    @GetMapping
    public Items<KnowledgeDocuments.Summary> list(@PathVariable String slug) {
        return new Items<>(documents.list(tenant(slug)));
    }

    @GetMapping("/{id}")
    public KnowledgeDocuments.Document get(@PathVariable String slug, @PathVariable UUID id) {
        return documents.get(tenant(slug), id).orElseThrow(KnowledgeController::notFound);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeDocuments.Document add(Authentication auth, @PathVariable String slug,
                                           @Valid @RequestBody DocumentForm form) {
        return documents.add(tenant(slug), form.title().strip(), form.body().strip(), auth.getName());
    }

    @PutMapping("/{id}")
    public KnowledgeDocuments.Document update(Authentication auth, @PathVariable String slug, @PathVariable UUID id,
                                              @Valid @RequestBody DocumentForm form) {
        return documents.update(tenant(slug), id, form.title().strip(), form.body().strip(), auth.getName())
            .orElseThrow(KnowledgeController::notFound);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String slug, @PathVariable UUID id) {
        if (!documents.remove(tenant(slug), id)) {
            throw notFound();
        }
    }

    private UUID tenant(String slug) {
        return tenants.findBySlug(slug).map(Tenant::getId)
            .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "No company with that slug"));
    }

    private static ApiException notFound() {
        return ApiException.notFound("DOCUMENT_NOT_FOUND", "No document with that id");
    }
}
