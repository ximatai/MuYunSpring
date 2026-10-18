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
                || !TransactionSynchronizationManager.isSynchronizationActive()) return;
        Pending pending = pending();
        var fields = BusinessContentFields.definitions(change.owner(), change.record());
        var field = fields.get(change.field());
        if (field != null) pending.changes.put(BusinessContentSaveLifecycleListener.source(change.owner(), change.record(), field.key()), change);
    }

    /** Persisted fact adapters supply a live scope, never pre-authorized permission or model snapshots. */
    public void recorded(BusinessContentSubmissionScope.Submission submission) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Business content submission requires the owning transaction");
        pending().recorded.add(Objects.requireNonNull(submission));
    }

    private Pending pending() {
        return TransactionSynchronizationManager.getSynchronizations().stream()
                .filter(Pending.class::isInstance).map(Pending.class::cast).filter(item -> item.owner() == this)
                .findFirst().orElseGet(() -> {
                    var created = new Pending(); TransactionSynchronizationManager.registerSynchronization(created); return created;
                });
    }

    private void submit(BusinessContentOriginalChanged change) {
        submit(() -> adapters.orderedStream().map(adapter -> adapter.captureSubmission(change))
                .flatMap(Optional::stream).toList());
    }
    private void submit(BusinessContentSubmissionScope.Submission submission) {
        submit(() -> List.of(submission));
    }
    private void submit(java.util.function.Supplier<List<BusinessContentSubmissionScope.Submission>> select) {
        // Missing credentials, revoked viewing rights or unsupported custom entries never reject the original save.
        try {
            nested.executeWithoutResult(status -> {
                // A route adapter may report false after swallowing a database exception. Roll back
                // the savepoint even for that decision, so a PostgreSQL aborted connection is recovered.
                if (!gateway.isTextGenerationAvailable()) { status.setRollbackOnly(); return; }
                var submissions = select.get();
                if (submissions.size() != 1) return;
                for (String language : languages) {
                    var result = generations.requestSubmitted(submissions.getFirst(), language);
                    if ("UNAVAILABLE".equals(result.state())) { status.setRollbackOnly(); return; }
                }
            });
        } catch (RuntimeException unavailable) {
            // Savepoints isolate queue failures from the original. No source text, identities, route or exception message.
            LOG.warn("Business content pre-generation was not registered ({})", unavailable.getClass().getSimpleName());
        }
    }

    private final class Pending implements TransactionSynchronization {
        private final Map<BusinessContentSource, BusinessContentOriginalChanged> changes = new LinkedHashMap<>();
        private final Set<BusinessContentSubmissionScope.Submission> recorded = new LinkedHashSet<>();
        BusinessContentSubmissionService owner() { return BusinessContentSubmissionService.this; }
        @Override public void beforeCommit(boolean readOnly) {
            if (!readOnly) {
                List.copyOf(changes.values()).forEach(BusinessContentSubmissionService.this::submit);
                List.copyOf(recorded).forEach(BusinessContentSubmissionService.this::submit);
            }
        }
        @Override public void afterCompletion(int status) { changes.clear(); recorded.clear(); }
    }
}
