package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.Objects;
import java.util.Map;

@Service
public class DynamicWorkflowModuleRecordGuard implements WorkflowModuleRecordGuard {
    private final DynamicRecordService dynamicRecordService;
    private final WorkflowSubmitActionPolicyResolver submissionPolicies;

    public DynamicWorkflowModuleRecordGuard(DynamicRecordService dynamicRecordService,
                                            WorkflowSubmitActionPolicyResolver submissionPolicies) {
        this.dynamicRecordService = Objects.requireNonNull(dynamicRecordService, "dynamicRecordService");
        this.submissionPolicies = Objects.requireNonNull(submissionPolicies, "submissionPolicies");
    }

    @Override
    public void beforeSubmit(WorkflowSubmitRequest request) {
        if (mainEntityAliasOrNull(request.moduleAlias()) != null) {
            requireRecordAction(request.moduleAlias(), request.recordId(), submissionPolicies.resolve(request));
        }
    }

    @Override
    public void requireRecordAction(String moduleAlias, String recordId, ActionExecutionPolicy policy) {
        String entityAlias = mainEntityAliasOrNull(moduleAlias);
        if (entityAlias == null) {
            return;
        }
        dynamicRecordService.requireRecordActionScope(moduleAlias, entityAlias, policy,
                Set.of(recordId), CurrentUserContext.currentUser());
        if (dynamicRecordService.selectSystem(moduleAlias, entityAlias, recordId) == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.dynamic-workflow-module-record-guard.record-not-found",
                    "dynamic record not found: {value1}.{value2}"), Map.of("value1", String.valueOf(moduleAlias), "value2", String.valueOf(recordId)));
        }
    }

    private String mainEntityAliasOrNull(String moduleAlias) {
        try {
            return dynamicRecordService.mainEntityAlias(moduleAlias);
        } catch (ModuleDefinitionException ignored) {
            return null;
        }
    }
}
