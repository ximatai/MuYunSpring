package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.deletion.*;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.database.core.annotation.Table;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** Soft deletion retains history behind the owning record; final deletion removes its copies atomically. */
@Component
public final class BusinessContentDeletionObserver implements DeletionLifecycleObserver {
    private final BusinessContentStore store;
    private final BusinessContentTranslationStore translations;
    private final BusinessContentGenerationStore generations;
    public BusinessContentDeletionObserver(BusinessContentStore store, BusinessContentTranslationStore translations,
            BusinessContentGenerationStore generations) {
        this.store = Objects.requireNonNull(store); this.translations = Objects.requireNonNull(translations);
        this.generations = Objects.requireNonNull(generations);
    }
    @Override public void succeeded(CrudAbility<?> ability, EntityContract entity, DeletionContext context,
                                    DeletionNode node, DeletionMode mode) {
        if (mode != DeletionMode.HARD) return;
        purge(ability, entity);
    }
    @Override public void retainedRecordPurged(CrudAbility<?> ability, EntityContract entity) {
        purge(ability, entity);
    }
    private void purge(CrudAbility<?> ability, EntityContract entity) {
        Class<?> type = ability.modelClass() == null ? entity.getClass() : ability.modelClass();
        if (!(entity instanceof DynamicRecord) && !type.isAnnotationPresent(Table.class)) return;
        store.purgeRecord(entity.getTenantId(), ability.getModuleAlias(),
                BusinessContentFields.entityKey(ability, entity), entity.getId());
        translations.purgeRecord(entity.getTenantId(), ability.getModuleAlias(),
                BusinessContentFields.entityKey(ability, entity), entity.getId());
        generations.purgeRecord(entity.getTenantId(), ability.getModuleAlias(),
                BusinessContentFields.entityKey(ability, entity), entity.getId());
    }
}
