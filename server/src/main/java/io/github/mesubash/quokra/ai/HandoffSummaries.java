package io.github.mesubash.quokra.ai;

import io.github.mesubash.quokra.conversation.AgentRef;
import io.github.mesubash.quokra.conversation.ConversationService;
import io.github.mesubash.quokra.conversation.HandedOff;
import io.github.mesubash.quokra.tenant.TenantRepository;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// When a chat joins the queue, the AI summarises it for the agent taking over (v3 step 6), as a private note
// by "AI summary". Off the request thread; without AI, or when it fails, there's simply no note.
@Slf4j
@Component
public class HandoffSummaries {

    public static final AgentRef AUTHOR = new AgentRef("quokra-ai", "AI summary");

    private final ReplySuggester suggester;
    private final ConversationService conversations;
    private final TenantRepository tenants;
    private final AiClient ai;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public HandoffSummaries(ReplySuggester suggester, ConversationService conversations, TenantRepository tenants,
                            AiClient ai) {
        this.suggester = suggester;
        this.conversations = conversations;
        this.tenants = tenants;
        this.ai = ai;
    }

    @TransactionalEventListener
    public void on(HandedOff handedOff) {
        if (ai.enabled()) {
            executor.execute(() -> summarize(handedOff));
        }
    }

    // Public for tests, which run in a transaction that never commits.
    public void summarize(HandedOff handedOff) {
        try {
            var conversation = conversations.get(handedOff.conversationId());
            var tenant = tenants.findById(conversation.getTenantId()).orElseThrow();
            String summary = suggester.summarize(tenant, conversations.messages(conversation.getId(), null, 30));
            if (summary != null) {
                conversations.addNote(conversation.getId(), AUTHOR, summary);
            }
        } catch (RuntimeException e) {
            log.warn("Handoff summary failed for conversation {}", handedOff.conversationId(), e);
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdown();
    }
}
