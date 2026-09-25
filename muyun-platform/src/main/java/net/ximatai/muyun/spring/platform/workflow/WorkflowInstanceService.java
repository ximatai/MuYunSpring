package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.constraint.StaticFieldWriteRules;
import net.ximatai.muyun.spring.common.model.constraint.WriteOperation;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.stereotype.Service;

@Service
public class WorkflowInstanceService extends AbstractAbilityService<WorkflowInstance> implements
        SoftDeleteAbility<WorkflowInstance> {
    public static final String MODULE_ALIAS = "platform.workflow.instance";

    public WorkflowInstanceService(BaseDao<WorkflowInstance, String> workflowInstanceDao) {
        super(MODULE_ALIAS, WorkflowInstance.class, workflowInstanceDao);
    }

    @Override
    public void beforeInsert(WorkflowInstance instance) {
        normalizeAndValidate(instance);
        // The workflow runtime writer also invokes this hook before its internal DAO insert.
        StaticFieldWriteRules.validate(WorkflowInstance.class, instance, WriteOperation.INSERT);
        rejectDuplicateRunningApprovalInstance(instance);
    }

    @Override
    public void beforeUpdate(WorkflowInstance instance) {
        WorkflowInstance existing = selectIncludingDeleted(instance.getId());
        if (existing != null && existing.getApprovalCompletedAt() != null) {
            rejectApprovalStatusRollback(instance);
        }
        normalizeAndValidate(instance);
    }

    private void normalizeAndValidate(WorkflowInstance instance) {
        if (instance.getVersionNo() == null || instance.getVersionNo() <= 0) {
            throw new PlatformException("workflow version number must be positive");
        }
        instance.setModuleAlias(PlatformNameRules.requireModuleAlias(instance.getModuleAlias()));
        if (instance.getApprovalEnabled() == null) {
            instance.setApprovalEnabled(Boolean.FALSE);
        }
        if (instance.getApprovalStatus() == null) {
            instance.setApprovalStatus(Boolean.TRUE.equals(instance.getApprovalEnabled())
                    ? WorkflowApprovalStatus.PROCESSING : WorkflowApprovalStatus.NONE);
        }
        if (instance.getInstanceStatus() == null) {
            instance.setInstanceStatus(WorkflowInstanceStatus.RUNNING);
        }
    }

    private void rejectDuplicateRunningApprovalInstance(WorkflowInstance instance) {
        if (!Boolean.TRUE.equals(instance.getApprovalEnabled())) {
            return;
        }
        boolean exists = existsOtherInCurrentScope(instance, Criteria.of()
                .eq("moduleAlias", instance.getModuleAlias())
                .eq("recordId", instance.getRecordId())
                .eq("approvalEnabled", Boolean.TRUE)
                .eq("instanceStatus", WorkflowInstanceStatus.RUNNING));
        if (exists) {
            throw new PlatformException("approval workflow already running for record: " + instance.getRecordId());
        }
    }

    private void rejectApprovalStatusRollback(WorkflowInstance instance) {
        if (instance.getApprovalStatus() != WorkflowApprovalStatus.APPROVED) {
            throw new PlatformException("approval status cannot rollback after approval completed");
        }
    }

}
