package net.ximatai.muyun.spring.platform.workflow;

public record WorkflowModuleTaskProcessBundle(
        String taskId,
        String instanceId,
        String workflowVersionId,
        String nodeKey,
        String moduleAlias,
        String recordId,
        WorkflowModuleTaskCompletionPolicy completionPolicy,
        WorkflowModuleTaskContext workflowTaskContext,
        WorkflowTaskDefinition taskDefinition,
        WorkflowModuleTaskEvaluation evaluation,
        WorkflowTaskGuide nextGuide
) {
}
