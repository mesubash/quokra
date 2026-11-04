package io.github.mesubash.quokra.ai;

import io.github.mesubash.quokra.conversation.AiQuestion;
import io.github.mesubash.quokra.conversation.ConversationService;
import io.github.mesubash.quokra.tenant.TenantRepository;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

// Answers a customer's typed question with the AI (v3 step 3b) once the question is saved, off the request
// thread, so the customer's message isn't held up and no transaction waits on the provider. Whatever happens,
// the conversation carries on: with the answer, or as the bot's usual miss.
@Slf4j
@Component
public class CustomerAnswers {

    private final ReplySuggester suggester;
    private final ConversationService conversations;
    private final TenantRepository tenants;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public CustomerAnswers(ReplySuggester suggester, ConversationService conversations, TenantRepository tenants) {
        this.suggester = suggester;
        this.conversations = conversations;
        this.tenants = tenants;
    }

    // Runs after the commit, still on the customer's request thread, so their IP is at hand for the per-IP limit.
    @TransactionalEventListener
    public void on(AiQuestion question) {
        String ip = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request
            ? request.getRequest().getRemoteAddr() : null;
        executor.execute(() -> answer(question, ip));
    }

    // Public for tests, which run in a transaction that never commits.
    public void answer(AiQuestion question, String ip) {
        ReplySuggester.Suggestion answer = ReplySuggester.Suggestion.none(ReplySuggester.Reason.AI_UNAVAILABLE);
        try {
            var conversation = conversations.get(question.conversationId());
            var tenant = tenants.findById(conversation.getTenantId()).orElseThrow();
            answer = suggester.answerCustomer(tenant, conversations.messages(conversation.getId(), null, 20), ip);
        } catch (RuntimeException e) {
            log.warn("AI answer failed for conversation {}", question.conversationId(), e);
        }
        try {
            conversations.finishAiQuestion(question.conversationId(), question.messageId(), answer.text(),
                answer.sources());
        } catch (RuntimeException e) {
            log.warn("Couldn't finish the AI answer for conversation {}", question.conversationId(), e);
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdown();
    }
}
