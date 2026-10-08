package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.action.ActionMessage;
import net.ximatai.muyun.spring.ability.action.MutationContextHolder;
import net.ximatai.muyun.spring.common.platform.ModuleRecordActionCommand;
import net.ximatai.muyun.spring.common.platform.ModuleRecordActionExecutor;
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
        var task = WorkflowMutationLock.task(tasks, taskId);
        var process = runtime.prepare(taskId, operatorId);
        var guide = process.evaluation().guides().stream().filter(item -> guideKey.equals(item.getGuideKey()))
                .findFirst().orElseThrow(() -> new PlatformException("任务办理指引不存在: " + guideKey));
        if (guide.getGuideKind() != WorkflowTaskGuideKind.OPEN_FORM && guide.getGuideKind() != WorkflowTaskGuideKind.EXECUTE_ACTION)
            throw new PlatformException("该指引不支持业务写入");
        var instance = instances.findById(task.getInstanceId());
        if (guide.getTargetModuleAlias() != null && !instance.getModuleAlias().equals(guide.getTargetModuleAlias()))
            throw new PlatformException("写入指引必须绑定当前任务业务，跨模块动作由领域执行器管理");
        String action = guide.getGuideKind() == WorkflowTaskGuideKind.OPEN_FORM ? "update" : guide.getTargetActionCode();
        if (action == null || action.isBlank()) throw new PlatformException("业务办理指引缺少动作编码");
        if (guide.getGuideKind() == WorkflowTaskGuideKind.OPEN_FORM || "update".equals(action))
            WorkflowTaskFormPolicy.requireValues(guide, values);
        if (payload != null && !payload.isEmpty()) throw new PlatformException("业务动作参数由已发布办理指引提供，不能由客户端覆盖");
        Map<String, Object> configuredPayload = Map.of();
        if (guide.getGuideConfigText() != null && !guide.getGuideConfigText().isBlank()) {
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var parameters = mapper.readTree(guide.getGuideConfigText()).path("payload");
                if (!parameters.isMissingNode()) configuredPayload = mapper.convertValue(parameters,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception failure) { throw new PlatformException("业务动作参数配置无效", failure); }
        }
        var executor = executors.orderedStream().filter(item -> item.supports(instance.getModuleAlias(), action)).findFirst()
                .orElseThrow(() -> new PlatformException("业务动作执行器不存在: " + action));
        Object businessResult = executor.executeApprovalBusiness(new ModuleRecordActionCommand(instance.getModuleAlias(), instance.getRecordId(),
                action, version, values, configuredPayload));
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
