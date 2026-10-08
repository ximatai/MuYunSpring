package net.ximatai.muyun.spring.platform.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class WorkflowApprovalRevocationPolicyTest {
    private final WorkflowInstance instance = new WorkflowInstance();
    private final WorkflowNodeInstance original = node("original", WorkflowNodeStatus.COMPLETED);
    private final WorkflowNodeInstance downstream = node("downstream", WorkflowNodeStatus.ACTIVE);
    private final WorkflowTask vote = task("vote", original.getId(), WorkflowTaskStatus.DONE);
    private final WorkflowTask next = task("next", downstream.getId(), WorkflowTaskStatus.TODO);
    private final WorkflowRouteInstance route = new WorkflowRouteInstance();
    WorkflowApprovalRevocationPolicyTest() {
        instance.setInstanceStatus(WorkflowInstanceStatus.RUNNING);
        vote.setActualProcessorId("actor"); vote.setDecision("approve");
        route.setSourceNodeKey(original.getNodeKey()); route.setTargetNodeKey(downstream.getNodeKey()); route.setRouteStatus(WorkflowRouteStatus.EFFECTIVE);
    }
    @Test void firstUntouchedLinearApprovalMayBeRewoundOnlyByItsActualProcessor() {
        assertThat(allowed("actor")).isTrue(); assertThat(allowed("other")).isFalse();
    }
    @Test void activeMultiVoteNodeAllowsOnlyItsOwnApprovedVote() {
        original.setNodeStatus(WorkflowNodeStatus.ACTIVE);
        assertThat(allowed("actor")).isTrue(); assertThat(allowed("other")).isFalse();
        vote.setDecision("reject"); assertThat(allowed("actor")).isFalse();
    }
    @ParameterizedTest @EnumSource(value = WorkflowTaskStatus.class, names = {"DONE", "TRANSFERRED", "REJECTED", "ROLLED_BACK", "CANCELED"})
    void anyProcessedDownstreamTaskPreventsRevocation(WorkflowTaskStatus status) { next.setTaskStatus(status); assertThat(allowed("actor")).isFalse(); }
    @Test void branchMilestoneCompletedApprovalAndOtherActivePathPreventRevocation() {
        original.setBranchRunId("branch:1"); assertThat(allowed("actor")).isFalse(); original.setBranchRunId(null);
        downstream.setNodeType(WorkflowNodeType.MILESTONE); assertThat(allowed("actor")).isFalse(); downstream.setNodeType(WorkflowNodeType.APPROVAL);
        instance.setApprovalStatus(WorkflowApprovalStatus.APPROVED); assertThat(allowed("actor")).isFalse(); instance.setApprovalStatus(WorkflowApprovalStatus.NONE);
        instance.setInstanceStatus(WorkflowInstanceStatus.COMPLETED); assertThat(allowed("actor")).isFalse(); instance.setInstanceStatus(WorkflowInstanceStatus.RUNNING);
        var other = node("other", WorkflowNodeStatus.ACTIVE);
        assertThat(WorkflowApprovalRevocationPolicy.allowed(vote, instance, original, List.of(original, downstream, other), List.of(route), List.of(vote, next), "actor")).isFalse();
    }
    @Test void oldCanceledTaskDoesNotBlockRevocationButACanceledTaskFromCurrentActivationDoes() {
        var activation = java.time.Instant.parse("2026-10-08T00:00:00Z"); downstream.setActivatedAt(activation);
        next.setCreatedAt(activation);
        var previous = task("previous", downstream.getId(), WorkflowTaskStatus.CANCELED); previous.setCreatedAt(activation.minusSeconds(1));
        assertThat(WorkflowApprovalRevocationPolicy.allowed(vote, instance, original, List.of(original, downstream), List.of(route), List.of(vote, next, previous), "actor")).isTrue();
        previous.setCreatedAt(activation);
        assertThat(WorkflowApprovalRevocationPolicy.allowed(vote, instance, original, List.of(original, downstream), List.of(route), List.of(vote, next, previous), "actor")).isFalse();
    }
    private boolean allowed(String actor) { return WorkflowApprovalRevocationPolicy.allowed(vote, instance, original, List.of(original, downstream), List.of(route), List.of(vote, next), actor); }
    private static WorkflowNodeInstance node(String key, WorkflowNodeStatus status) {
        var node = new WorkflowNodeInstance(); node.setId(key); node.setNodeKey(key); node.setNodeType(WorkflowNodeType.APPROVAL); node.setApprovalMode(WorkflowApprovalMode.ALL); node.setNodeStatus(status); return node;
    }
    private static WorkflowTask task(String id, String node, WorkflowTaskStatus status) {
        var task = new WorkflowTask(); task.setId(id); task.setNodeInstanceId(node); task.setTaskKind(WorkflowTaskKind.APPROVAL); task.setTaskStatus(status); return task;
    }
}
