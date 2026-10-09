package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import java.util.Objects;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class StaticWorkflowModuleRecordGuard implements WorkflowModuleRecordGuard {
    private final List<CrudAbility<?>> abilities;
    private final WorkflowSubmitActionPolicyResolver submissionPolicies;
    private final ActionExecutionPolicyService authorization;

    public StaticWorkflowModuleRecordGuard(List<CrudAbility<?>> abilities,
                                           WorkflowSubmitActionPolicyResolver submissionPolicies,
                                           ActionExecutionPolicyService authorization) {
        this.abilities = List.copyOf(Objects.requireNonNull(abilities, "abilities"));
        this.submissionPolicies = Objects.requireNonNull(submissionPolicies, "submissionPolicies");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
    }

    @Override
    public void beforeSubmit(WorkflowSubmitRequest request) {
        if (ability(request.moduleAlias()).isPresent()) {
            requireRecordAction(request.moduleAlias(), request.recordId(), submissionPolicies.resolve(request));
        }
    }

    @Override
    public void requireRecordAction(String moduleAlias, String recordId, ActionExecutionPolicy policy) {
        Optional<CrudAbility<?>> matched = ability(moduleAlias);
        if (matched.isEmpty()) {
            return;
        }
        authorization.requireRecordAction(ActionExecutionContext.ofPolicy(moduleAlias, policy,
                Set.of(recordId), CurrentUserContext.currentUser()));
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
}
