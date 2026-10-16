package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.common.content.ContentWriteContext;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import net.ximatai.muyun.spring.common.content.ContentPolicy;
import java.util.*;

/** Same transactional source capture for static records and dynamic aggregate children. */
@Component
public final class BusinessContentSaveLifecycleListener implements EntitySaveLifecycleListener {
    private final BusinessContentStore store;
    private final ApplicationEventPublisher events;
    private final ThreadLocal<Map<EntityContract, Map<String, BusinessContentStore.Baseline>>> baselines =
            ThreadLocal.withInitial(IdentityHashMap::new);
    public BusinessContentSaveLifecycleListener(BusinessContentStore store) { this(store, ignored -> { }); }
    @Autowired
    public BusinessContentSaveLifecycleListener(BusinessContentStore store, ApplicationEventPublisher events) {
        this.store = Objects.requireNonNull(store); this.events = Objects.requireNonNull(events);
    }

    @Override public <T extends EntityContract> void beforeSave(CrudAbility<T> ability, T existing, T incoming) {
        var fields = BusinessContentFields.definitions(ability, incoming);
        var inputs = ContentWriteContext.inputs(incoming);
        if (!fields.keySet().containsAll(inputs.keySet())) throw new IllegalArgumentException("Source language submitted for an undeclared content field");
        if (fields.isEmpty()) return;
        if (!TransactionScopeSupport.isTransactionActive()) throw new IllegalStateException("Business content requires a transaction");
        inputs.forEach((field, input) -> BusinessContentStore.checkExpected(store.latest(source(ability, incoming,
                fields.get(field).key())), input));
        T before = existing == null ? ability.selectActiveRaw(incoming.getId()) : existing;
        if (before != null) {
            Map<String, BusinessContentStore.Baseline> snapshots = new LinkedHashMap<>();
            fields.forEach((field, definition) -> {
                var previous = store.latest(source(ability, before, definition.key()));
                String text = BusinessContentFields.text(before, field);
                if (previous == null || !Objects.equals(previous.getOriginalText(), text))
                    snapshots.put(field, new BusinessContentStore.Baseline(text));
            });
            if (!snapshots.isEmpty()) baselines.get().put(incoming, Map.copyOf(snapshots));
        }
    }

    @Override public <T extends EntityContract> void persisted(CrudAbility<T> ability, T incoming) {
        var before = removeBaseline(incoming);
        var fields = BusinessContentFields.definitions(ability, incoming);
        if (fields.isEmpty()) return;
        // Persisted raw values include omitted dynamic fields and final formula/normalization results.
        T persisted = ability.selectActiveRaw(incoming.getId());
        if (persisted == null) throw new IllegalStateException("Saved business content record is unavailable");
        var inputs = ContentWriteContext.inputs(incoming);
        fields.forEach((field, definition) -> {
            var revision = store.capture(source(ability, persisted, definition.key()),
                    BusinessContentFields.text(persisted, field), inputs.get(field), before.get(field));
            if (revision != null && definition.policy() == ContentPolicy.AUTO_TRANSLATE
                    && revision.getOriginalText() != null && !revision.getOriginalText().isBlank()
                    && revision.getSourceLanguage() != null)
                events.publishEvent(new BusinessContentOriginalChanged(ability, persisted, field, revision.getId()));
        });
        if (incoming instanceof net.ximatai.muyun.spring.common.content.ContentLanguageInputs carrier) {
            Runnable restore = carrier.consumeContentLanguageInputs();
            TransactionScopeSupport.afterCompletionOrNow(() -> {}, restore);
        }
    }

    @Override public <T extends EntityContract> void persistFailed(CrudAbility<T> ability, T incoming, RuntimeException failure) {
        removeBaseline(incoming);
    }

    private Map<String, BusinessContentStore.Baseline> removeBaseline(EntityContract record) {
        var snapshots = baselines.get().remove(record);
        if (baselines.get().isEmpty()) baselines.remove();
        return snapshots == null ? Map.of() : snapshots;
    }

    static BusinessContentSource source(CrudAbility<?> ability, EntityContract record, String fieldKey) {
        return new BusinessContentSource(record.getTenantId(), ability.getModuleAlias(),
                BusinessContentFields.entityKey(ability, record), record.getId(), fieldKey);
    }
}
