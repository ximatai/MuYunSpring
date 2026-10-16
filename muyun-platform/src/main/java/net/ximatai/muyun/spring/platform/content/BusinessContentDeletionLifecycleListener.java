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
public final class BusinessContentDeletionLifecycleListener implements DeletionLifecycleListener {
    private final BusinessContentStore store;
    public BusinessContentDeletionLifecycleListener(BusinessContentStore store) { this.store = Objects.requireNonNull(store); }
    @Override public DeletionLifecycleSession open(DeletionResource root) {
        return new DeletionLifecycleSession() {
            @Override public void succeeded(CrudAbility<?> ability, EntityContract entity, DeletionContext context,
                                            DeletionNode node, DeletionMode mode) {
                if (mode != DeletionMode.HARD) return;
                Class<?> type = ability.modelClass() == null ? entity.getClass() : ability.modelClass();
                if (!(entity instanceof DynamicRecord) && !type.isAnnotationPresent(Table.class)) return;
                store.purgeRecord(entity.getTenantId(), ability.getModuleAlias(),
                        BusinessContentFields.entityKey(ability, entity), entity.getId());
            }
        };
    }
}
