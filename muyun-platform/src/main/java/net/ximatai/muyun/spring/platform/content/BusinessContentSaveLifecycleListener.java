package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.common.content.ContentWriteContext;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import org.springframework.stereotype.Component;
import java.util.*;

/** Same transactional source capture for static records and dynamic aggregate children. */
@Component
public final class BusinessContentSaveLifecycleListener implements EntitySaveLifecycleListener {
    private final BusinessContentStore store;
    public BusinessContentSaveLifecycleListener(BusinessContentStore store) { this.store = Objects.requireNonNull(store); }

    @Override public <T extends EntityContract> void beforeSave(CrudAbility<T> ability, T existing, T incoming) {
        var fields = BusinessContentFields.definitions(ability, incoming);
        var inputs = ContentWriteContext.inputs(incoming);
        if (!fields.keySet().containsAll(inputs.keySet())) throw new IllegalArgumentException("Source language submitted for an undeclared content field");
        if (fields.isEmpty()) return;
        if (!TransactionScopeSupport.isTransactionActive()) throw new IllegalStateException("Business content requires a transaction");
        inputs.forEach((field, input) -> BusinessContentStore.checkExpected(store.latest(source(ability, incoming,
                fields.get(field).key())), input));
    }

    @Override public <T extends EntityContract> void persisted(CrudAbility<T> ability, T incoming) {
        var fields = BusinessContentFields.definitions(ability, incoming);
        if (fields.isEmpty()) return;
        // Persisted raw values include omitted dynamic fields and final formula/normalization results.
        T persisted = ability.selectActiveRaw(incoming.getId());
        if (persisted == null) throw new IllegalStateException("Saved business content record is unavailable");
        var inputs = ContentWriteContext.inputs(incoming);
        fields.forEach((field, definition) -> store.capture(source(ability, persisted, definition.key()),
                BusinessContentFields.text(persisted, field), inputs.get(field)));
        if (incoming instanceof net.ximatai.muyun.spring.common.content.ContentLanguageInputs carrier) {
            Runnable restore = carrier.consumeContentLanguageInputs();
            TransactionScopeSupport.afterCompletionOrNow(() -> {}, restore);
        }
    }

    static BusinessContentSource source(CrudAbility<?> ability, EntityContract record, String fieldKey) {
        return new BusinessContentSource(record.getTenantId(), ability.getModuleAlias(),
                BusinessContentFields.entityKey(ability, record), record.getId(), fieldKey);
    }
}
