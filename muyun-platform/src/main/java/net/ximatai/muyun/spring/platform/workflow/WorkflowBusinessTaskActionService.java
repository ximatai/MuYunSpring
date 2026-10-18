package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.content.ContentWriteContext.SourceInput;

import net.ximatai.muyun.spring.ability.action.ActionMessage;
import net.ximatai.muyun.spring.ability.action.MutationContextHolder;
import net.ximatai.muyun.spring.common.platform.ModuleRecordActionCommand;
import net.ximatai.muyun.spring.common.platform.ModuleRecordActionExecutor;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.List;

/** Business mutation, live completion check and workflow advancement commit or roll back together. */
@Service
public class WorkflowBusinessTaskActionService {
    private final WorkflowTaskDao tasks;
    private final WorkflowInstanceDao instances;
    private final WorkflowModuleTaskRuntimeService runtime;
    private final WorkflowTaskActionFacade actions;
    private final ObjectProvider<ModuleRecordActionExecutor> executors;
    public WorkflowBusinessTaskActionService(WorkflowTaskDao tasks, WorkflowInstanceDao instances,
            WorkflowModuleTaskRuntimeService runtime, WorkflowTaskActionFacade actions,
            ObjectProvider<ModuleRecordActionExecutor> executors) {
        this.tasks = tasks; this.instances = instances; this.runtime = runtime; this.actions = actions; this.executors = executors;
    }
    @Transactional
    public Result execute(String taskId, String guideKey, Integer version, Map<String, Object> values,
                          Map<String, Object> payload, String operatorId, String reason) {
        return execute(taskId, guideKey, version, values, payload, operatorId, reason, List.of());
    }
    @Transactional
    public Result execute(String taskId, String guideKey, Integer version, Map<String, Object> values,
                          Map<String, Object> payload, String operatorId, String reason,
                          List<WorkflowManualRouteSelection> manualRouteSelections) {
        return execute(taskId, guideKey, version, values, payload, operatorId, reason, manualRouteSelections, Map.of());
    }
    @Transactional
    public Result execute(String taskId, String guideKey, Integer version, Map<String, Object> values,
                          Map<String, Object> payload, String operatorId, String reason,
                          List<WorkflowManualRouteSelection> manualRouteSelections,
                          Map<String, SourceInput> contentLanguages) {
        var task = WorkflowMutationLock.task(tasks, taskId);
        var process = runtime.prepare(taskId, operatorId);
        var guide = process.evaluation().guides().stream().filter(item -> guideKey.equals(item.getGuideKey()))
                .findFirst().orElseThrow(() -> PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-business-task-action.task-guide-not-found",
                        "任务办理指引不存在: {value1}"), Map.of("value1", String.valueOf(guideKey))));
        if (guide.getGuideKind() != WorkflowTaskGuideKind.OPEN_FORM && guide.getGuideKind() != WorkflowTaskGuideKind.EXECUTE_ACTION)
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-business-task-action.this-guide-does-not-support-business-writes",
                    "该指引不支持业务写入"));
        var instance = instances.findById(task.getInstanceId());
        if (guide.getTargetModuleAlias() != null && !instance.getModuleAlias().equals(guide.getTargetModuleAlias()))
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-business-task-action.the-write-guide-must-target-the-current-task-s-record-cross-module",
                    "写入指引必须绑定当前任务业务，跨模块动作由领域执行器管理"));
        String action = guide.getGuideKind() == WorkflowTaskGuideKind.OPEN_FORM ? "update" : guide.getTargetActionCode();
        if (action == null || action.isBlank()) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow-business-task-action.the-task-guide-is-missing-an-action-code",
                "业务办理指引缺少动作编码"));
        if (guide.getGuideKind() == WorkflowTaskGuideKind.OPEN_FORM || "update".equals(action))
            WorkflowTaskFormPolicy.requireValues(guide, values);
        if (contentLanguages != null && !contentLanguages.isEmpty()
                && guide.getGuideKind() != WorkflowTaskGuideKind.OPEN_FORM && !"update".equals(action))
            throw new IllegalArgumentException("Source languages belong to a task business-field update");
        if (payload != null && !payload.isEmpty()) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow-business-task-action.action-parameters-come-from-the-published-guide-and-cannot-be-overridden",
                "业务动作参数由已发布办理指引提供，不能由客户端覆盖"));
        Map<String, Object> configuredPayload = Map.of();
        if (guide.getGuideConfigText() != null && !guide.getGuideConfigText().isBlank()) {
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var parameters = mapper.readTree(guide.getGuideConfigText()).path("payload");
                if (!parameters.isMissingNode()) configuredPayload = mapper.convertValue(parameters,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception failure) { throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-business-task-action.invalid-action-parameter-configuration",
                    "业务动作参数配置无效"), Map.of(), failure); }
        }
        var executor = executors.orderedStream().filter(item -> item.supports(instance.getModuleAlias(), action)).findFirst()
                .orElseThrow(() -> PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-business-task-action.business-action-executor-not-found",
                        "业务动作执行器不存在: {value1}"), Map.of("value1", String.valueOf(action))));
        Object businessResult = executor.executeApprovalBusiness(new ModuleRecordActionCommand(instance.getModuleAlias(), instance.getRecordId(),
                action, version, values, configuredPayload, contentLanguages));
        var result = actions.execute("complete", WorkflowTaskActionRequest.builder(taskId, operatorId).reason(reason)
                .manualRouteSelections(manualRouteSelections).build());
        MutationContextHolder.current().ifPresent(context -> {
            WorkflowMutationFacts.recordChanged(instance);
            context.message(ActionMessage.success("workflow.business-task.completed", "业务任务已完成"));
        });
        return new Result(businessResult, result);
    }
    public record Result(Object businessResult, WorkflowTaskActionResult actionResult) {}
}
