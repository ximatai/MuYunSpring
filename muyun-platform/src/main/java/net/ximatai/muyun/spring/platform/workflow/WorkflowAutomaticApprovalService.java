package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/** Opt-in consecutive approval; notices pass through, manual route selection stops automation. */
@Service
public class WorkflowAutomaticApprovalService {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);
    private final WorkflowTaskDao tasks;
    private final WorkflowNodeInstanceDao nodes;
    private final ObjectProvider<WorkflowTaskActionService> actions;
    private final ThreadLocal<Boolean> advancing = new ThreadLocal<>();
    public WorkflowAutomaticApprovalService(WorkflowTaskDao tasks, WorkflowNodeInstanceDao nodes,
                                            ObjectProvider<WorkflowTaskActionService> actions) {
        this.tasks = tasks; this.nodes = nodes; this.actions = actions;
    }
    @Transactional
    public void continueFor(String instanceId, String actor, Instant at) {
        if (Boolean.TRUE.equals(advancing.get())) return;
        advancing.set(true);
        try {
            while (true) {
                var graph = nodes.query(Criteria.of().eq("instanceId", instanceId), ALL);
                var eligible = tasks.query(Criteria.of().eq("instanceId", instanceId).eq("taskStatus", WorkflowTaskStatus.TODO), ALL)
                        .stream().filter(task -> task.getTaskKind() == WorkflowTaskKind.APPROVAL
                                && actor.equals(task.getOriginalAssigneeId()) && actor.equals(task.getAssigneeId())
                                && task.getAssignmentKind() == WorkflowAssignmentKind.NORMAL)
                        .filter(task -> graph.stream().anyMatch(node -> node.getId().equals(task.getNodeInstanceId())
                                && Boolean.TRUE.equals(node.getAutoApproveSameUser()) && node.getNodeStatus() == WorkflowNodeStatus.ACTIVE
                                && graph.stream().noneMatch(branch -> branch.getRouteMode() == WorkflowRouteMode.MANUAL
                                && node.getNodeKey().equals(branch.getSelectorNodeKey()))))
                        .findFirst();
                if (eligible.isEmpty()) return;
                actions.getObject().approveAutomatically(WorkflowTaskActionRequest.builder(eligible.get().getId(), actor).reason("同一办理人连续审批，按已发布规则自动通过").operatedAt(at).build());
            }
        } finally { advancing.remove(); }
    }
}
