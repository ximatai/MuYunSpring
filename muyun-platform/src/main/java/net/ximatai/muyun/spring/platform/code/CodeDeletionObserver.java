package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.ability.code.CodeAbility;
import net.ximatai.muyun.spring.ability.deletion.*;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetProvider;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** Soft deletion retains ownership; final removal releases the original issuance facts. */
@Component
public final class CodeDeletionObserver implements DeletionLifecycleObserver {
    private final CodeOccupationService occupations;
    public CodeDeletionObserver(CodeOccupationService occupations) { this.occupations = Objects.requireNonNull(occupations); }
    @Override public void succeeded(CrudAbility<?> service, EntityContract record, DeletionContext context, DeletionNode node, DeletionMode mode) {
        if (mode == DeletionMode.HARD) release(service, record);
    }
    @Override public void retainedRecordPurged(CrudAbility<?> service, EntityContract record) { release(service, record); }
    private void release(CrudAbility<?> service, EntityContract record) {
        try (var tenant = net.ximatai.muyun.spring.common.tenant.TenantContext.use(record.getTenantId())) {
            releaseInTenant(service, record);
        }
    }
    private void releaseInTenant(CrudAbility<?> service, EntityContract record) {
        if (service instanceof CodeAbility<?> coded) occupations.releaseAll(service.getModuleAlias(), coded.codeEntityAlias(), record.getId());
        else if (service instanceof ReferenceTargetProvider provider && record instanceof net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord) {
            var target = provider.referenceTarget();
            occupations.releaseAll(target.moduleAlias(), target.entityAlias(), record.getId());
        }
    }
}
