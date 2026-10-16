package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.spring.platform.ai.AiModelGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import java.util.*;

/** Source facts become durable jobs before the original transaction commits; model calls remain asynchronous. */
@Service
public final class BusinessContentSubmissionService {
    private static final Logger LOG = LoggerFactory.getLogger(BusinessContentSubmissionService.class);
    private static final Set<String> ALLOWED = Set.of("zh-CN", "en", "de");
    private final AiModelGateway gateway;
    private final BusinessContentGenerationService generations;
    private final ObjectProvider<BusinessContentSubmissionScope> adapters;
    private final List<String> languages;
    private final TransactionTemplate nested;

    public BusinessContentSubmissionService(AiModelGateway gateway, BusinessContentGenerationService generations,
            ObjectProvider<BusinessContentSubmissionScope> adapters, PlatformTransactionManager transactions,
            @Value("${muyun.content.primary-languages:zh-CN,en,de}") String languages) {
        this.gateway = gateway; this.generations = generations; this.adapters = adapters;
        this.languages = Arrays.stream(languages.split(",")).map(String::strip).filter(value -> !value.isEmpty()).distinct().toList();
        if (!ALLOWED.containsAll(this.languages)) throw new IllegalArgumentException("Unsupported primary content language");
        nested = new TransactionTemplate(transactions);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @EventListener
    public void originalChanged(BusinessContentOriginalChanged change) {
        if (languages.isEmpty() || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive() || !available()) return;
        Pending pending = TransactionSynchronizationManager.getSynchronizations().stream()
                .filter(Pending.class::isInstance).map(Pending.class::cast).filter(item -> item.owner() == this)
                .findFirst().orElseGet(() -> {
                    var created = new Pending(); TransactionSynchronizationManager.registerSynchronization(created); return created;
                });
        var fields = BusinessContentFields.definitions(change.owner(), change.record());
        var field = fields.get(change.field());
        if (field != null) pending.changes.put(BusinessContentSaveLifecycleListener.source(change.owner(), change.record(), field.key()), change);
    }

    private boolean available() {
        try { return gateway.isTextGenerationAvailable(); }
        catch (RuntimeException unavailable) { return false; }
    }

    private void submit(BusinessContentOriginalChanged change) {
        // Missing credentials, revoked viewing rights or unsupported custom entries never reject the original save.
        if (!available()) return;
        try {
            nested.executeWithoutResult(status -> {
                var submissions = adapters.orderedStream().map(adapter -> adapter.captureSubmission(change))
                        .flatMap(Optional::stream).toList();
                if (submissions.size() != 1) return;
                for (String language : languages) generations.requestSubmitted(submissions.getFirst(), language);
            });
        } catch (RuntimeException unavailable) {
            // Savepoints isolate queue failures from the original. No source text, identities, route or exception message.
            LOG.warn("Business content pre-generation was not registered ({})", unavailable.getClass().getSimpleName());
        }
    }

    private final class Pending implements TransactionSynchronization {
        private final Map<BusinessContentSource, BusinessContentOriginalChanged> changes = new LinkedHashMap<>();
        BusinessContentSubmissionService owner() { return BusinessContentSubmissionService.this; }
        @Override public void beforeCommit(boolean readOnly) {
            if (!readOnly) List.copyOf(changes.values()).forEach(BusinessContentSubmissionService.this::submit);
        }
        @Override public void afterCompletion(int status) { changes.clear(); }
    }
}
