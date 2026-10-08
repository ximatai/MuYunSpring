package net.ximatai.muyun.spring.platform.workflow;

public record WorkflowModuleTaskContext(
        String workflowTaskId,
        WorkflowModuleTaskCompletionPolicy completionPolicy
) {
}
