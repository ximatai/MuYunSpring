package net.ximatai.muyun.spring.platform.workflow;

import java.util.List;

/** A vote may be retracted only while its immediate linear continuation is untouched. */
final class WorkflowApprovalRevocationPolicy {
    private WorkflowApprovalRevocationPolicy() { }
    static boolean allowed(WorkflowTask task, WorkflowInstance instance, WorkflowNodeInstance node,
                           List<WorkflowNodeInstance> nodes, List<WorkflowRouteInstance> routes,
                           List<WorkflowTask> tasks, String operatorId) {
        if (task.getTaskKind() != WorkflowTaskKind.APPROVAL || task.getTaskStatus() != WorkflowTaskStatus.DONE
                || !"approve".equals(task.getDecision()) || operatorId == null
                || !operatorId.equals(task.getActualProcessorId())
                || instance.getInstanceStatus() != WorkflowInstanceStatus.RUNNING
                || instance.getApprovalStatus() == WorkflowApprovalStatus.APPROVED || node == null) return false;
        if (node.getNodeStatus() == WorkflowNodeStatus.ACTIVE) return true;
        if (node.getNodeStatus() != WorkflowNodeStatus.COMPLETED || node.getBranchRunId() != null) return false;
        var outgoing = routes.stream().filter(route -> node.getNodeKey().equals(route.getSourceNodeKey())
                && route.getRouteStatus() == WorkflowRouteStatus.EFFECTIVE).toList();
        if (outgoing.size() != 1) return false;
        var next = nodes.stream().filter(item -> outgoing.getFirst().getTargetNodeKey().equals(item.getNodeKey()))
                .findFirst().orElse(null);
        if (next == null || next.getNodeType() != WorkflowNodeType.APPROVAL
                || next.getApprovalMode() == WorkflowApprovalMode.NOTICE
                || next.getNodeStatus() != WorkflowNodeStatus.ACTIVE) return false;
        // Earlier canceled rounds retain audit rows; only tasks received in this activation can prove it untouched.
        var nextTasks = tasks.stream().filter(item -> next.getId().equals(item.getNodeInstanceId()))
                .filter(item -> next.getActivatedAt() == null || item.getCreatedAt() == null
                        || !item.getCreatedAt().isBefore(next.getActivatedAt())).toList();
        return !nextTasks.isEmpty() && nextTasks.stream().allMatch(item -> item.getTaskStatus() == WorkflowTaskStatus.TODO)
                && nodes.stream().noneMatch(item -> item.getNodeStatus() == WorkflowNodeStatus.ACTIVE
                && !next.getId().equals(item.getId()));
    }
}
