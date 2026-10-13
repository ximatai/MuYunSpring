package net.ximatai.muyun.spring.platform.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.id.Ids;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class WorkflowRuntimeEventFactory {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public WorkflowEvent instanceStarted(WorkflowInstance instance, String operatorId, Instant occurredAt) {
        return event(instance, null, null, WorkflowEventType.INSTANCE_STARTED, "submit", operatorId,
                net.ximatai.muyun.spring.common.i18n.TextReference.frozenCode("platform.workflow.event.instance-started", "workflow instance started"), null, occurredAt);
    }

    public WorkflowEvent nodeActivated(WorkflowInstance instance, WorkflowNodeInstance node,
                                       String operatorId, Instant occurredAt) {
        return event(instance, node, null, WorkflowEventType.NODE_ACTIVATED, null, operatorId,
                net.ximatai.muyun.spring.common.i18n.TextReference.frozenCode("platform.workflow.event.node-activated", "workflow node activated"), null, occurredAt);
    }

    public WorkflowEvent taskCreated(WorkflowInstance instance, WorkflowNodeInstance node, WorkflowTask task,
                                     String operatorId, Instant occurredAt) {
        return event(instance, node, task, WorkflowEventType.TASK_CREATED, null, operatorId,
                net.ximatai.muyun.spring.common.i18n.TextReference.frozenCode("platform.workflow.event.task-created", "workflow task created"), null, occurredAt);
    }

    public WorkflowEvent taskCreated(WorkflowInstance instance, WorkflowTask task,
                                     String operatorId, Instant occurredAt) {
        return event(instance, null, task, WorkflowEventType.TASK_CREATED, null, operatorId,
                net.ximatai.muyun.spring.common.i18n.TextReference.frozenCode("platform.workflow.event.task-created", "workflow task created"), null, occurredAt);
    }

    public WorkflowEvent taskCompleted(WorkflowInstance instance, WorkflowTask task, String actionCode,
                                       String operatorId, String reason, Instant occurredAt) {
        return taskCompleted(instance, task, actionCode, operatorId,
                reason == null || reason.isBlank() ? "workflow task completed" : reason, reason, occurredAt);
    }

    public WorkflowEvent taskCompleted(WorkflowInstance instance, WorkflowTask task, String actionCode,
                                       String operatorId, String message, String reason, Instant occurredAt) {
        return opinionEvent(instance, null, task, WorkflowEventType.TASK_COMPLETED, actionCode, operatorId,
                message, null, occurredAt, reason);
    }

    public WorkflowEvent taskTransferred(WorkflowInstance instance, WorkflowTask task, String operatorId,
                                         String message, String payloadText, Instant occurredAt) {
        return opinionEvent(instance, null, task, WorkflowEventType.TASK_TRANSFERRED, "transfer", operatorId,
                message, payloadText, occurredAt, payloadText);
    }

    public WorkflowEvent delegationCompleted(WorkflowInstance instance, WorkflowTask sourceTask,
                                             String operatorId, String payloadText, Instant occurredAt) {
        return event(instance, null, sourceTask, WorkflowEventType.DELEGATION_COMPLETED, "delegation_completed",
                operatorId, net.ximatai.muyun.spring.common.i18n.TextReference.frozenCode("platform.workflow.event.delegation-completed", "workflow delegation completed notice created"), payloadText, occurredAt);
    }

    public WorkflowEvent addSign(WorkflowInstance instance, WorkflowNodeInstance node, WorkflowTask task,
                                 String operatorId, String message, String payloadText, Instant occurredAt) {
        return opinionEvent(instance, node, task, WorkflowEventType.ADD_SIGN, "addSign", operatorId,
                message == null || message.isBlank() ? "workflow runtime add sign" : message,
                payloadText, occurredAt, message);
    }

    public WorkflowEvent taskRejected(WorkflowInstance instance, WorkflowTask task,
                                      String operatorId, String message, Instant occurredAt) {
        return opinionEvent(instance, null, task, WorkflowEventType.TASK_REJECTED, "reject", operatorId,
                message == null || message.isBlank() ? "workflow task rejected" : message, null, occurredAt, message);
    }

    public WorkflowEvent taskResubmitted(WorkflowInstance instance, WorkflowTask task,
                                         String operatorId, String reason, Instant occurredAt) {
        return taskResubmitted(instance, task, operatorId,
                reason == null || reason.isBlank() ? "workflow task resubmitted" : reason, reason, occurredAt);
    }

    public WorkflowEvent taskResubmitted(WorkflowInstance instance, WorkflowTask task,
                                         String operatorId, String message, String reason, Instant occurredAt) {
        return opinionEvent(instance, null, task, WorkflowEventType.TASK_RESUBMITTED, "resubmit", operatorId,
                message, null, occurredAt, reason);
    }

    public WorkflowEvent nodeRolledBack(WorkflowInstance instance, WorkflowNodeInstance node,
                                        String operatorId, String message, Instant occurredAt) {
        return opinionEvent(instance, node, null, WorkflowEventType.NODE_ROLLED_BACK, "rollback", operatorId,
                message == null || message.isBlank() ? "workflow node rolled back" : message, null, occurredAt, message);
    }

    public WorkflowEvent overtimeWarned(WorkflowInstance instance, WorkflowNodeInstance node,
                                        String operatorId, String payloadText, Instant occurredAt) {
        return event(instance, node, null, WorkflowEventType.OVERTIME_WARNED, "overtime_warn", operatorId,
                net.ximatai.muyun.spring.common.i18n.TextReference.frozenCode("platform.workflow.event.overtime-warned", "workflow node overtime warned"), payloadText, occurredAt);
    }

    public WorkflowEvent overtimeOverdue(WorkflowInstance instance, WorkflowNodeInstance node,
                                         String operatorId, String payloadText, Instant occurredAt) {
        return event(instance, node, null, WorkflowEventType.OVERTIME_OVERDUE, "overtime_overdue", operatorId,
                net.ximatai.muyun.spring.common.i18n.TextReference.frozenCode("platform.workflow.event.overtime-overdue", "workflow node overdue"), payloadText, occurredAt);
    }

    public WorkflowEvent taskSkipped(WorkflowInstance instance, WorkflowTask task,
                                     String operatorId, String message, Instant occurredAt) {
        return event(instance, null, task, WorkflowEventType.TASK_SKIPPED, "skip", operatorId,
                message == null || message.isBlank() ? "workflow task skipped" : message, null, occurredAt);
    }

    public WorkflowEvent taskInvalidated(WorkflowInstance instance, WorkflowTask task,
                                         String operatorId, String message, Instant occurredAt) {
        return opinionEvent(instance, null, task, WorkflowEventType.TASK_INVALIDATED, "invalidate", operatorId,
                message == null || message.isBlank() ? "workflow task invalidated" : message, null, occurredAt, message);
    }

    public WorkflowEvent taskCanceled(WorkflowInstance instance, WorkflowTask task,
                                      String operatorId, String message, Instant occurredAt) {
        return opinionEvent(instance, null, task, WorkflowEventType.TASK_CANCELED, "cancel", operatorId,
                message == null || message.isBlank() ? "workflow task canceled" : message, null, occurredAt, message);
    }

    public WorkflowEvent routeSelected(WorkflowInstance instance, WorkflowRouteInstance route,
                                       String operatorId, Instant occurredAt) {
        return event(instance, null, null, WorkflowEventType.ROUTE_SELECTED, "route", operatorId,
                "workflow route selected: " + route.getRouteKey(), routePayload(route), occurredAt);
    }

    public WorkflowEvent routeDropped(WorkflowInstance instance, WorkflowRouteInstance route,
                                      String operatorId, Instant occurredAt) {
        return event(instance, null, null, WorkflowEventType.ROUTE_DROPPED, "route", operatorId,
                "workflow route dropped: " + route.getRouteKey(), routePayload(route), occurredAt);
    }

    public WorkflowEvent approvalCompleted(WorkflowInstance instance, String operatorId, Instant occurredAt) {
        return event(instance, null, null, WorkflowEventType.APPROVAL_COMPLETED, "approval_completed", operatorId,
                net.ximatai.muyun.spring.common.i18n.TextReference.frozenCode("platform.workflow.event.approval-completed", "workflow approval completed"), null, occurredAt);
    }

    public WorkflowEvent instanceCompleted(WorkflowInstance instance, String operatorId, Instant occurredAt) {
        return event(instance, null, null, WorkflowEventType.INSTANCE_COMPLETED, "complete", operatorId,
                net.ximatai.muyun.spring.common.i18n.TextReference.frozenCode("platform.workflow.event.instance-completed", "workflow instance completed"), null, occurredAt);
    }

    public WorkflowEvent instanceRevoked(WorkflowInstance instance, String operatorId,
                                         String message, Instant occurredAt) {
        return opinionEvent(instance, null, null, WorkflowEventType.INSTANCE_REVOKED, "revoke", operatorId,
                message == null || message.isBlank() ? "workflow instance revoked" : message, null, occurredAt, message);
    }

    public WorkflowEvent instanceReset(WorkflowInstance instance, String operatorId,
                                       String message, Instant occurredAt) {
        return opinionEvent(instance, null, null, WorkflowEventType.INSTANCE_RESET, "reset", operatorId,
                message == null || message.isBlank() ? "workflow instance reset" : message, null, occurredAt, message);
    }

    public WorkflowEvent instanceTerminated(WorkflowInstance instance, String operatorId,
                                            String message, Instant occurredAt) {
        return opinionEvent(instance, null, null, WorkflowEventType.INSTANCE_TERMINATED, "terminate", operatorId,
                message == null || message.isBlank() ? "workflow instance terminated" : message, null, occurredAt, message);
    }

    public WorkflowEvent instanceForceTerminated(WorkflowInstance instance, String operatorId,
                                                 String message, Instant occurredAt) {
        return opinionEvent(instance, null, null, WorkflowEventType.INSTANCE_TERMINATED, "forceTerminate", operatorId,
                message == null || message.isBlank() ? "workflow instance force terminated" : message, null, occurredAt, message);
    }

    private WorkflowEvent opinionEvent(WorkflowInstance instance, WorkflowNodeInstance node, WorkflowTask task,
                                       WorkflowEventType eventType, String actionCode, String operatorId,
                                       String message, String payloadText, Instant occurredAt, String reason) {
        WorkflowEvent event = event(instance, node, task, eventType, actionCode, operatorId,
                message, payloadText, occurredAt);
        event.setReason(reason == null || reason.isBlank() ? null : reason);
        return event;
    }

    private WorkflowEvent event(WorkflowInstance instance, WorkflowNodeInstance node, WorkflowTask task,
                                WorkflowEventType eventType, String actionCode, String operatorId,
                                net.ximatai.muyun.spring.common.i18n.TextReference source, String payloadText, Instant occurredAt) {
        var event = event(instance, node, task, eventType, actionCode, operatorId, source.originalText(), payloadText, occurredAt);
        try { event.setMessageSourceText(OBJECT_MAPPER.writeValueAsString(source)); }
        catch (JsonProcessingException failure) { throw new PlatformException("cannot freeze workflow system message source", failure); }
        return event;
    }

    private WorkflowEvent event(WorkflowInstance instance, WorkflowNodeInstance node, WorkflowTask task,
                                WorkflowEventType eventType, String actionCode, String operatorId,
                                String message, String payloadText, Instant occurredAt) {
        WorkflowEvent event = new WorkflowEvent();
        event.setId(Ids.newId());
        event.setTenantId(instance.getTenantId());
        event.setInstanceId(instance.getId());
        event.setNodeInstanceId(node == null ? null : node.getId());
        event.setTaskId(task == null ? null : task.getId());
        event.setEventType(eventType);
        event.setActionCode(actionCode);
        event.setOperatorId(operatorId);
        event.setMessage(message);
        event.setPayloadText(payloadText);
        event.setOccurredAt(occurredAt == null ? Instant.now() : occurredAt);
        return event;
    }

    private String routePayload(WorkflowRouteInstance route) {
        boolean addedByAddSign = Boolean.TRUE.equals(route.getAddedByAddSign());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("routeId", route.getId());
        payload.put("routeKey", route.getRouteKey());
        payload.put("branchNodeKey", route.getBranchNodeKey());
        payload.put("sourceNodeKey", route.getSourceNodeKey());
        payload.put("targetNodeKey", route.getTargetNodeKey());
        payload.put("addedByAddSign", addedByAddSign);
        payload.put("isAddSignRoute", addedByAddSign);
        payload.put("addSignSourceNodeKey", route.getAddSignSourceNodeKey());
        payload.put("closedReason", route.getClosedReason());
        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new PlatformException("workflow route event payload serialize failed: " + route.getId(), ex);
        }
    }
}
