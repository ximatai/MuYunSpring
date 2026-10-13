package net.ximatai.muyun.spring.platform.workflow;

import java.time.Instant;
import java.util.List;

public record WorkflowAdminInstanceView(
        String instanceId,
        String moduleAlias,
        String recordId,
        String definitionId,
        String workflowVersionId,
        Integer versionNo,
        WorkflowInstanceStatus instanceStatus,
        WorkflowApprovalStatus approvalStatus,
        String startedBy,
        String startedByTitle,
        Instant startedAt,
        List<String> activeNodeKeys,
        List<String> activeNodeTitles,
        List<String> currentTaskIds,
        List<String> currentAssigneeIds,
        List<String> currentAssigneeTitles,
        WorkflowOvertimeStatus overtimeStatus,
        Instant updatedAt,
        Instant lastOperatedAt,
        List<WorkflowNodeTitleView> activeNodeLabels
) {
    public WorkflowAdminInstanceView {
        activeNodeLabels = activeNodeLabels == null ? List.of() : List.copyOf(activeNodeLabels);
        activeNodeKeys = activeNodeKeys == null ? List.of() : List.copyOf(activeNodeKeys);
        activeNodeTitles = activeNodeTitles == null ? List.of() : List.copyOf(activeNodeTitles);
        currentTaskIds = currentTaskIds == null ? List.of() : List.copyOf(currentTaskIds);
        currentAssigneeIds = currentAssigneeIds == null ? List.of() : List.copyOf(currentAssigneeIds);
        currentAssigneeTitles = currentAssigneeTitles == null ? List.of() : List.copyOf(currentAssigneeTitles);
    }
}
