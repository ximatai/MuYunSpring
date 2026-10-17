package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.id.Ids;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public class WorkflowDelegationCompletionNoticeService {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final WorkflowTaskDao taskDao;
    private final WorkflowEventWriter eventWriter;
    private final WorkflowRuntimeEventFactory eventFactory;

    public WorkflowDelegationCompletionNoticeService(WorkflowTaskDao taskDao,
                                                     WorkflowEventWriter eventWriter,
                                                     WorkflowRuntimeEventFactory eventFactory) {
        this.taskDao = taskDao;
        this.eventWriter = eventWriter;
        this.eventFactory = eventFactory;
    }

    public WorkflowTask createIfNeeded(WorkflowInstance instance, WorkflowTask completedTask,
                                       String actualProcessorId, Instant completedAt) {
        if (instance == null || completedTask == null || !hasDelegationSource(completedTask)) {
            return null;
        }
        String principalId = completedTask.getDelegatedFromUserId();
        if (!hasText(principalId) || Objects.equals(principalId, actualProcessorId)) {
            return null;
        }
        if (alreadyCreated(completedTask.getId())) {
            return null;
        }

        Instant now = completedAt == null ? Instant.now() : completedAt;
        WorkflowTask notice = noticeTask(instance, completedTask, principalId, actualProcessorId, now);
        EntityLifecycle.prepareInsert(notice, now);
        taskDao.insert(notice);
        eventWriter.insert(eventFactory.delegationCompleted(instance, completedTask, actualProcessorId,
                eventPayload(completedTask, notice, actualProcessorId), now));
        return notice;
    }

    private boolean hasDelegationSource(WorkflowTask task) {
        if (task.getAssignmentKind() == WorkflowAssignmentKind.DELEGATED) {
            return true;
        }
        return hasText(task.getDelegatedFromUserId())
                && (hasText(task.getDelegatedToUserId())
                || hasText(task.getDelegationPolicyId())
                || hasText(task.getAssignmentSnapshotText()));
    }

    private boolean alreadyCreated(String sourceTaskId) {
        List<WorkflowTask> existing = taskDao.query(Criteria.of()
                .eq("taskKind", WorkflowTaskKind.NOTICE)
                .eq("parentTaskId", sourceTaskId), ALL);
        return !existing.isEmpty();
    }

    private WorkflowTask noticeTask(WorkflowInstance instance, WorkflowTask sourceTask, String principalId,
                                    String actualProcessorId, Instant now) {
        WorkflowTask task = new WorkflowTask();
        task.setId(Ids.newId());
        task.setTenantId(instance.getTenantId());
        task.setInstanceId(instance.getId());
        task.setNodeInstanceId(sourceTask.getNodeInstanceId());
        task.setTaskKind(WorkflowTaskKind.NOTICE);
        task.setTaskStatus(WorkflowTaskStatus.TODO);
        task.setParentTaskId(sourceTask.getId());
        task.setOriginTaskId(sourceTask.getOriginTaskId() == null ? sourceTask.getId() : sourceTask.getOriginTaskId());
        task.setAssignmentKind(sourceTask.getAssignmentKind());
        task.setOwnerId(principalId);
        task.setOriginalAssigneeId(firstText(sourceTask.getOriginalAssigneeId(), principalId));
        task.setAssigneeId(principalId);
        task.setActualProcessorId(actualProcessorId);
        task.setDelegatedFromUserId(sourceTask.getDelegatedFromUserId());
        task.setDelegatedToUserId(sourceTask.getDelegatedToUserId());
        task.setPrincipalCanProcess(sourceTask.getPrincipalCanProcess());
        task.setDelegationPolicyId(sourceTask.getDelegationPolicyId());
        task.setTransferredFromUserId(sourceTask.getTransferredFromUserId());
        task.setTransferredBy(sourceTask.getTransferredBy());
        task.setTransferredAt(sourceTask.getTransferredAt());
        task.setDecision("delegation_completed");
        task.setResultMessage("workflow delegation completed by " + actualProcessorId);
        task.setAssignmentPolicyText(sourceTask.getAssignmentPolicyText());
        task.setAssignmentSnapshotText(noticeSnapshot(sourceTask, actualProcessorId));
        task.setCheckStatus(sourceTask.getCheckStatus());
        task.setCompletedAt(now);
        return task;
    }

    private String noticeSnapshot(WorkflowTask sourceTask, String actualProcessorId) {
        return "{\"noticeSourceType\":\"DELEGATION_COMPLETED\""
                + ",\"sourceTaskId\":\"" + escape(sourceTask.getId()) + "\""
                + ",\"originTaskId\":\"" + escape(sourceTask.getOriginTaskId()) + "\""
                + ",\"delegationPolicyId\":\"" + escape(sourceTask.getDelegationPolicyId()) + "\""
                + ",\"delegatedFromUserId\":\"" + escape(sourceTask.getDelegatedFromUserId()) + "\""
                + ",\"delegatedToUserId\":\"" + escape(sourceTask.getDelegatedToUserId()) + "\""
                + ",\"actualProcessorId\":\"" + escape(actualProcessorId) + "\""
                + ",\"sourceAssignmentKind\":\"" + (sourceTask.getAssignmentKind() == null
                ? "" : sourceTask.getAssignmentKind().name()) + "\"}";
    }

    private String eventPayload(WorkflowTask sourceTask, WorkflowTask noticeTask, String actualProcessorId) {
        return "{\"sourceTaskId\":\"" + escape(sourceTask.getId()) + "\""
                + ",\"noticeTaskId\":\"" + escape(noticeTask.getId()) + "\""
                + ",\"originalAssigneeId\":\"" + escape(sourceTask.getOriginalAssigneeId()) + "\""
                + ",\"delegatedFromUserId\":\"" + escape(sourceTask.getDelegatedFromUserId()) + "\""
                + ",\"delegatedToUserId\":\"" + escape(sourceTask.getDelegatedToUserId()) + "\""
                + ",\"delegationPolicyId\":\"" + escape(sourceTask.getDelegationPolicyId()) + "\""
                + ",\"actualProcessorId\":\"" + escape(actualProcessorId) + "\""
                + ",\"resultTaskStatus\":\"" + (sourceTask.getTaskStatus() == null
                ? "" : sourceTask.getTaskStatus().name()) + "\"}";
    }

    private String firstText(String... values) {
        for (String value : values == null ? new String[0] : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
