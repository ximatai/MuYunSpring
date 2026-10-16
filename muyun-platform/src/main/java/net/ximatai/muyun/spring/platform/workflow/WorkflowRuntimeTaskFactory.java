package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.id.Ids;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class WorkflowRuntimeTaskFactory {
    private final WorkflowRuntimeEventFactory eventFactory;
    private final java.util.Optional<WorkflowDelegationService> delegationService;
    private final WorkflowParticipantService participants;

    public WorkflowRuntimeTaskFactory(WorkflowRuntimeEventFactory eventFactory,
                                       java.util.Optional<WorkflowDelegationService> delegationService,
                                       WorkflowParticipantService participants) {
        this.eventFactory = eventFactory;
        this.delegationService = delegationService;
        this.participants = participants;
    }

    public WorkflowRuntimeTaskDraft createBlockingTasks(WorkflowInstance instance,
                                                        List<WorkflowNodeInstance> nodeInstances,
                                                        WorkflowActivationResult activation,
                                                        String operatorId,
                                                        Instant occurredAt) {
        Map<String, WorkflowNodeInstance> nodesByKey = nodeInstances.stream()
                .collect(Collectors.toMap(WorkflowNodeInstance::getNodeKey, Function.identity(), (left, right) -> left));
        List<WorkflowTask> tasks = new ArrayList<>();
        List<WorkflowEvent> events = new ArrayList<>();
        for (String nodeKey : activation.blockingApprovalNodeKeys()) {
            WorkflowNodeInstance node = nodesByKey.get(nodeKey);
            if (node == null) {
                continue;
            }
            createTasks(instance, node, WorkflowTaskKind.APPROVAL, tasks, events, operatorId, occurredAt);
        }
        for (String nodeKey : activation.blockingTaskNodeKeys()) {
            WorkflowNodeInstance node = nodesByKey.get(nodeKey);
            if (node == null) continue;
            createTasks(instance, node, WorkflowTaskKind.BUSINESS, tasks, events, operatorId, occurredAt);
        }
        for (String nodeKey : activation.activatedNodeKeys()) {
            var node = nodesByKey.get(nodeKey);
            if (node != null && node.getNodeType() == WorkflowNodeType.APPROVAL
                    && node.getApprovalMode() == WorkflowApprovalMode.NOTICE) {
                createTasks(instance, node, WorkflowTaskKind.NOTICE, tasks, events, operatorId, occurredAt);
            }
        }
        return new WorkflowRuntimeTaskDraft(tasks, events);
    }

    private void createTasks(WorkflowInstance instance, WorkflowNodeInstance node, WorkflowTaskKind kind,
                             List<WorkflowTask> tasks, List<WorkflowEvent> events, String operatorId, Instant at) {
        List<String> users = participants.resolve(instance, node);
        for (String userId : users) {
            var task = task(instance, node, kind, userId);
            tasks.add(task);
            events.add(eventFactory.taskCreated(instance, node, task, operatorId, at));
        }
        node.setRequiredTaskCount(users.size());
    }

    private WorkflowTask task(WorkflowInstance instance, WorkflowNodeInstance node,
                              WorkflowTaskKind taskKind, String ownerId) {
        WorkflowTask task = new WorkflowTask();
        task.setId(Ids.newId());
        task.setTenantId(instance.getTenantId());
        task.setInstanceId(instance.getId());
        task.setNodeInstanceId(node.getId());
        task.setTaskKind(taskKind);
        task.setTaskStatus(WorkflowTaskStatus.TODO);
        task.setOwnerId(ownerId);
        task.setOriginalAssigneeId(ownerId);
        task.setAssigneeId(ownerId);
        task.setAssignmentKind(WorkflowAssignmentKind.NORMAL);
        task.setAssignmentPolicyText(node.getParticipantPolicyText());
        task.setAssignmentSnapshotText(ownerId == null ? null : "{\"assigneeId\":\"" + escape(ownerId) + "\"}");
        task.setCheckStatus(taskKind == WorkflowTaskKind.BUSINESS
                ? WorkflowTaskCheckStatus.NOT_CHECKED : WorkflowTaskCheckStatus.NO_CHECK);
        applyDelegation(instance, task);
        return task;
    }

    private void applyDelegation(WorkflowInstance instance, WorkflowTask task) {
        if (delegationService.isEmpty() || task.getTaskKind() == WorkflowTaskKind.NOTICE
                || task.getTaskKind() == WorkflowTaskKind.RESUBMIT) {
            return;
        }
        WorkflowDelegationMatch match = delegationService.get().match(task.getOriginalAssigneeId(),
                instance.getModuleAlias(), instance.getAuthOrgId());
        if (match == null) {
            return;
        }
        task.setAssignmentKind(WorkflowAssignmentKind.DELEGATED);
        task.setAssigneeId(match.delegateUserId());
        task.setDelegatedFromUserId(match.principalUserId());
        task.setDelegatedToUserId(match.delegateUserId());
        task.setPrincipalCanProcess(match.principalCanProcess());
        task.setDelegationPolicyId(match.delegationPolicyId());
        task.setAssignmentSnapshotText(match.snapshotText());
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
