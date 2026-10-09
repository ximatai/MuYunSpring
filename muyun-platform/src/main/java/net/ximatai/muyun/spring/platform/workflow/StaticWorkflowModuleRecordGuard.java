package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import java.util.Objects;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class StaticWorkflowModuleRecordGuard implements WorkflowModuleRecordGuard {
    public static final String SUBMIT_ACTION_CODE = "submit";
    private static final ActionExecutionPolicy SUBMIT_POLICY = new ActionExecutionPolicy(
            SUBMIT_ACTION_CODE,
            PlatformActionLevel.RECORD,
            ActionAccessMode.AUTH_REQUIRED,
            true,
            true,
            ActionDefaultGrantPolicy.NONE,
            null
    );

    private static final ActionExecutionPolicy APPROVAL_SUBMIT_POLICY =
            WorkflowActionPolicyService.runtimePolicy("submitApproval");

    private final List<CrudAbility<?>> abilities;

    public StaticWorkflowModuleRecordGuard(List<CrudAbility<?>> abilities) {
        this.abilities = abilities == null ? List.of() : List.copyOf(abilities);
    }

    @Override
    public void beforeSubmit(WorkflowSubmitRequest request) {
        requireRecordAction(request.moduleAlias(), request.recordId(), currentActionPolicy(request));
    }

    @Override
    public void requireRecordAction(String moduleAlias, String recordId, ActionExecutionPolicy policy) {
        Optional<CrudAbility<?>> matched = ability(moduleAlias);
        if (matched.isEmpty()) {
            return;
        }
        CrudAbility<?> ability = matched.get();
        EntityContract record = selectVisibleRecord(ability, recordId, policy);
        if (record == null) {
            throw new PlatformException("static record not found: " + moduleAlias + "." + recordId);
        }
        if (!Objects.equals(record.getTenantId(), TenantContext.currentTenantId().orElse(null))) {
            throw new PlatformException("workflow record tenant does not match current tenant: " + moduleAlias + "." + recordId);
        }
    }

    private Optional<CrudAbility<?>> ability(String moduleAlias) {
        return abilities.stream()
                .filter(ability -> moduleAlias.equals(ability.getModuleAlias()))
                .findFirst();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private EntityContract selectVisibleRecord(CrudAbility<?> ability, String recordId, ActionExecutionPolicy policy) {
        if (ability instanceof DataScopeAbility dataScopeAbility) {
            DataScopeCriteriaResult scope = dataScopeAbility.requireRecordScopeResult(policy, Set.of(recordId));
            return (EntityContract) dataScopeAbility.withDataScopeTenant(scope, () -> ability.select(recordId));
        }
        return (EntityContract) ability.select(recordId);
    }

    private ActionExecutionPolicy currentActionPolicy(WorkflowSubmitRequest request) {
        return ActionExecutionContextHolder.current()
                .filter(context -> request.moduleAlias().equals(context.moduleAlias()))
                .filter(context -> !context.hasRecordContext() || context.recordIds().contains(request.recordId()))
                .map(context -> context.actionPolicy())
                .orElse(request.approvalRequired() ? APPROVAL_SUBMIT_POLICY : SUBMIT_POLICY);
    }
}
