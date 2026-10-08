package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.deletion.RecordDeletionGuard;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicEntityService;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** Workflow ownership is checked on the shared static/dynamic deletion path. */
@Component
public class WorkflowRecordDeletionGuard implements RecordDeletionGuard {
    private final WorkflowInstanceDao instances;
    private final DynamicRecordService records;
    public WorkflowRecordDeletionGuard(WorkflowInstanceDao instances, DynamicRecordService records) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.records = Objects.requireNonNull(records, "records");
    }
    @Override
    public void validate(CrudAbility<?> ability, EntityContract record) {
        if (record == null) return;
        if (ability instanceof DynamicEntityService && record instanceof DynamicRecord dynamic
                && !dynamic.getEntity().alias().equals(records.mainEntityAlias(ability.getModuleAlias()))) return;
        // Use the record's owner even when a verified mutation scope spans tenants.
        try (var tenant = TenantContext.use(record.getTenantId())) {
            WorkflowMutationLock.record(ability.getModuleAlias(), record.getId());
            var criteria = Criteria.of().eq("moduleAlias", ability.getModuleAlias()).eq("recordId", record.getId())
                    .eq("instanceStatus", WorkflowInstanceStatus.RUNNING).eq("deleted", false);
            if (record.getTenantId() == null) criteria.isNull("tenantId");
            else criteria.eq("tenantId", record.getTenantId());
            if (instances.count(criteria) > 0) throw new PlatformException(PlatformErrorCodes.RESOURCE_IN_USE, 409,
                    "该记录仍有运行中的流程，请先终止或重置流程后再删除");
        }
    }
}
