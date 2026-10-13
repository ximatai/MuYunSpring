package net.ximatai.muyun.spring.platform.workflow;

import java.time.Instant;

public record WorkflowAdminActiveTaskView(
        String taskId,
        String instanceId,
        String nodeInstanceId,
        String nodeKey,
        String nodeTitle,
        WorkflowTaskKind taskKind,
        WorkflowTaskStatus taskStatus,
        String assigneeId,
        String assigneeTitle,
        Instant createdAt,
        Instant receivedAt,
        WorkflowOvertimeStatus overtimeStatus,
        boolean canForceApprove,
        WorkflowAssignmentKind assignmentKind,
        String originalAssigneeId,
        String originalAssigneeTitle,
        String delegatedFromUserId,
        String delegatedFromUserTitle,
        String delegatedToUserId,
        String delegatedToUserTitle,
        Boolean principalCanProcess,
        String delegationPolicyId,
        String delegationSnapshot,
        Boolean addedByAddSign,
        String addSignSourceNodeKey,
        String addSignOperatorId,
        Instant addSignAt,
        String workflowVersionId
) {
    public WorkflowAdminActiveTaskView {
        boolean isAddedByAddSign = Boolean.TRUE.equals(addedByAddSign);
        addedByAddSign = isAddedByAddSign;
        if (!isAddedByAddSign) {
            addSignSourceNodeKey = null;
            addSignOperatorId = null;
            addSignAt = null;
        }
    }

    public static WorkflowAdminActiveTaskView from(WorkflowTask task, WorkflowNodeInstance node) {
        return from(task, node, java.util.Map.of(), null);
    }

    public static WorkflowAdminActiveTaskView from(WorkflowTask task, WorkflowNodeInstance node,
                                                   java.util.Map<String, String> userTitles, String workflowVersionId) {
        boolean addedByAddSign = Boolean.TRUE.equals(node.getAddedByAddSign());
        return new WorkflowAdminActiveTaskView(
                task.getId(),
                task.getInstanceId(),
                task.getNodeInstanceId(),
                node.getNodeKey(),
                firstText(node.getNodeTitle(), node.getNodeKey()),
                task.getTaskKind(),
                task.getTaskStatus(),
                task.getAssigneeId(),
                title(task.getAssigneeId(), userTitles),
                task.getCreatedAt(),
                task.getCreatedAt(),
                node.getOvertimeStatus(),
                true,
                task.getAssignmentKind(),
                task.getOriginalAssigneeId(),
                title(task.getOriginalAssigneeId(), userTitles),
                task.getDelegatedFromUserId(),
                title(task.getDelegatedFromUserId(), userTitles),
                task.getDelegatedToUserId(),
                title(task.getDelegatedToUserId(), userTitles),
                task.getPrincipalCanProcess(),
                task.getDelegationPolicyId(),
                task.getAssignmentSnapshotText(),
                addedByAddSign,
                addedByAddSign ? blankToNull(node.getAddSignSourceNodeKey()) : null,
                addedByAddSign ? blankToNull(node.getAddSignOperatorId()) : null,
                addedByAddSign ? node.getAddSignAt() : null, workflowVersionId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstText(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String title(String userId, java.util.Map<String, String> userTitles) {
        return userId == null || userTitles == null ? null : userTitles.get(userId);
    }
}
