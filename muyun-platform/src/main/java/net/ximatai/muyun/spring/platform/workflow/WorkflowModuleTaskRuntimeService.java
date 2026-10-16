package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class WorkflowModuleTaskRuntimeService {
    private final WorkflowTaskDao taskDao;
    private final WorkflowInstanceDao instanceDao;
    private final WorkflowNodeInstanceDao nodeDao;
    private final WorkflowBusinessTaskResolver specifications;
    private final WorkflowTaskAssignmentPolicyService assignments;
    private final WorkflowActionPolicyService actionPolicies;
    private final WorkflowModuleTaskEvaluator evaluator;
    private final WorkflowTaskActionFacade taskActionFacade;

    public WorkflowModuleTaskRuntimeService(WorkflowTaskDao taskDao,
                                            WorkflowInstanceDao instanceDao,
                                            WorkflowNodeInstanceDao nodeDao,
                                            WorkflowBusinessTaskResolver specifications, WorkflowTaskAssignmentPolicyService assignments,
                                            WorkflowActionPolicyService actionPolicies,
                                            WorkflowModuleTaskEvaluator evaluator,
                                            WorkflowTaskActionFacade taskActionFacade) {
        this.taskDao = taskDao;
        this.instanceDao = instanceDao;
        this.nodeDao = nodeDao;
        this.specifications = specifications;
        this.assignments = assignments;
        this.actionPolicies = actionPolicies;
        this.evaluator = evaluator;
        this.taskActionFacade = taskActionFacade;
    }

    public WorkflowModuleTaskProcessBundle prepare(String taskId, String operatorId) {
        Context context = requireContext(taskId, operatorId);
        return bundle(context);
    }

    @org.springframework.transaction.annotation.Transactional
    public WorkflowModuleTaskContinueResult checkAndContinue(String taskId, String operatorId, String reason) {
        return checkAndContinue(taskId, operatorId, reason, null, null);
    }

    @org.springframework.transaction.annotation.Transactional
    public WorkflowModuleTaskContinueResult checkAndContinue(String taskId, String operatorId, String reason,
                                                             String selectedRouteKey, String selectedReason) {
        return checkAndContinue(taskId, operatorId, reason, selectedRouteKey, selectedReason, List.of());
    }

    @org.springframework.transaction.annotation.Transactional
    public WorkflowModuleTaskContinueResult checkAndContinue(String taskId, String operatorId, String reason,
                                                             List<WorkflowManualRouteSelection> manualRouteSelections) {
        return checkAndContinue(taskId, operatorId, reason, null, null, manualRouteSelections);
    }

    @org.springframework.transaction.annotation.Transactional
    public WorkflowModuleTaskContinueResult checkAndContinue(String taskId, String operatorId, String reason,
                                                             String selectedRouteKey, String selectedReason,
                                                             List<WorkflowManualRouteSelection> manualRouteSelections) {
        Context context = requireContext(taskId, operatorId);
        WorkflowModuleTaskProcessBundle bundle = bundle(context);
        if (!bundle.evaluation().passed()) {
            return WorkflowModuleTaskContinueResult.blocked(bundle);
        }
        WorkflowTaskActionResult result = taskActionFacade.execute("complete",
                WorkflowTaskActionRequest.builder(taskId, operatorId)
                        .reason(reason)
                        .selectedRoute(selectedRouteKey, selectedReason)
                        .manualRouteSelections(manualRouteSelections)
                        .build());
        return WorkflowModuleTaskContinueResult.continued(result);
    }

    private WorkflowModuleTaskProcessBundle bundle(Context context) {
        WorkflowModuleTaskEvaluation evaluation = evaluator.evaluate(context.instance(), context.node(), context.task(),
                context.taskDefinition());
        WorkflowModuleTaskCompletionPolicy policy = completionPolicy(context.taskDefinition(), evaluation);
        return new WorkflowModuleTaskProcessBundle(
                context.task().getId(),
                context.instance().getId(),
                context.instance().getWorkflowVersionId(),
                context.node().getNodeKey(),
                context.instance().getModuleAlias(),
                context.instance().getRecordId(),
                policy,
                new WorkflowModuleTaskContext(context.task().getId(), policy),
                context.taskDefinition(),
                evaluation,
                evaluation.guides().isEmpty() ? null : evaluation.guides().getFirst()
        );
    }

    private WorkflowModuleTaskCompletionPolicy completionPolicy(WorkflowTaskDefinition definition,
                                                               WorkflowModuleTaskEvaluation evaluation) {
        if (evaluation.passed() && evaluation.checkStatus() == WorkflowTaskCheckStatus.PASSED) {
            return WorkflowModuleTaskCompletionPolicy.CHECK_PASSED;
        }
        if (!evaluation.guides().isEmpty()
                && evaluation.guides().getFirst().getGuideKind() == WorkflowTaskGuideKind.EXECUTE_ACTION) {
            return WorkflowModuleTaskCompletionPolicy.AFTER_ACTION_SUCCESS;
        }
        if (Boolean.TRUE.equals(definition.getManualConfirm())) {
            return WorkflowModuleTaskCompletionPolicy.MANUAL_CONFIRM;
        }
        return WorkflowModuleTaskCompletionPolicy.CHECK_PASSED;
    }

    private Context requireContext(String taskId, String operatorId) {
        WorkflowTask task = requireTask(taskId);
        if (task.getTaskKind() != WorkflowTaskKind.BUSINESS) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-module-task-runtime.this-is-not-a-business-task",
                    "workflow task is not a business task: {value1}"), Map.of("value1", String.valueOf(taskId)));
        }
        if (task.getTaskStatus() != WorkflowTaskStatus.TODO) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-module-task-runtime.this-task-is-no-longer-pending",
                    "workflow task is not todo: {value1}"), Map.of("value1", String.valueOf(taskId)));
        }
        if (!assignments.canProcess(task, operatorId)) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-module-task-runtime.you-are-not-this-task-s-assignee",
                    "workflow task action operator is not assignee: {value1}"), Map.of("value1", String.valueOf(taskId)));
        }
        WorkflowInstance instance = requireInstance(task);
        if (instance.getInstanceStatus() != WorkflowInstanceStatus.RUNNING) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-module-task-runtime.the-workflow-is-not-running",
                    "workflow instance is not running: {value1}"), Map.of("value1", String.valueOf(instance.getId())));
        }
        WorkflowNodeInstance node = requireNode(task);
        if (node.getNodeType() != WorkflowNodeType.TASK || node.getNodeStatus() != WorkflowNodeStatus.ACTIVE) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-module-task-runtime.this-node-is-not-an-active-task-node",
                    "workflow node is not active task node: {value1}"), Map.of("value1", String.valueOf(node.getNodeKey())));
        }
        actionPolicies.requireRuntimeAction(instance, "complete");
        WorkflowTaskDefinition definition = specifications.resolve(node).definition();
        return new Context(instance, node, task, definition);
    }

    private WorkflowTask requireTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-module-task-runtime.select-an-approval-task",
                    "workflow task id must not be blank"));
        }
        WorkflowTask task = WorkflowTenantScope.visible(taskDao.findById(taskId));
        if (task == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-module-task-runtime.approval-task-not-found",
                    "workflow task not found: {value1}"), Map.of("value1", String.valueOf(taskId)));
        }
        return task;
    }

    private WorkflowInstance requireInstance(WorkflowTask task) {
        WorkflowInstance instance = instanceDao.findById(task.getInstanceId());
        if (instance == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-module-task-runtime.workflow-instance-not-found",
                    "workflow instance not found: {value1}"), Map.of("value1", String.valueOf(task.getInstanceId())));
        }
        return instance;
    }

    private WorkflowNodeInstance requireNode(WorkflowTask task) {
        WorkflowNodeInstance node = nodeDao.findById(task.getNodeInstanceId());
        if (node == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-module-task-runtime.workflow-node-instance-not-found",
                    "workflow node instance not found: {value1}"), Map.of("value1", String.valueOf(task.getNodeInstanceId())));
        }
        return node;
    }

    private record Context(WorkflowInstance instance,
                           WorkflowNodeInstance node,
                           WorkflowTask task,
                           WorkflowTaskDefinition taskDefinition) {
    }
}
