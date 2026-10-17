package net.ximatai.muyun.spring.platform.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.platform.ActionAuthorizationResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WorkflowNodeOvertimeScanService {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);
    private static final String SYSTEM_OPERATOR = ActionAuthorizationResult.OPERATOR_SYSTEM;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final WorkflowNodeInstanceDao nodeDao;
    private final WorkflowInstanceDao instanceDao;
    private final WorkflowEventWriter eventWriter;
    private final WorkflowRuntimeEventFactory eventFactory;

    public WorkflowNodeOvertimeScanService(WorkflowNodeInstanceDao nodeDao,
                                           WorkflowInstanceDao instanceDao,
                                           WorkflowEventWriter eventWriter,
                                           WorkflowRuntimeEventFactory eventFactory) {
        this.nodeDao = nodeDao;
        this.instanceDao = instanceDao;
        this.eventWriter = eventWriter;
        this.eventFactory = eventFactory;
    }

    @Transactional
    public WorkflowNodeOvertimeScanResult scan(Instant now) {
        Instant effectiveNow = now == null ? Instant.now() : now;
        List<WorkflowNodeInstance> candidates = nodeDao.query(Criteria.of()
                        .eq("nodeStatus", WorkflowNodeStatus.ACTIVE)
                        .eq("nodeType", WorkflowNodeType.APPROVAL),
                ALL);
        int warned = 0;
        int overdue = 0;
        for (WorkflowNodeInstance node : candidates) {
            WorkflowOvertimeStatus targetStatus = targetStatus(node, effectiveNow);
            if (targetStatus == WorkflowOvertimeStatus.OVERDUE) {
                overdue += markOverdue(node, effectiveNow);
            } else if (targetStatus == WorkflowOvertimeStatus.WARNED) {
                warned += markWarned(node, effectiveNow);
            }
        }
        return new WorkflowNodeOvertimeScanResult(candidates.size(), warned, overdue);
    }

    private WorkflowOvertimeStatus targetStatus(WorkflowNodeInstance node, Instant now) {
        if (!isActiveApproval(node) || node.getActivatedAt() == null) {
            return null;
        }
        if (shouldMarkOverdue(node, now)) {
            return WorkflowOvertimeStatus.OVERDUE;
        }
        if (shouldMarkWarned(node, now)) {
            return WorkflowOvertimeStatus.WARNED;
        }
        return null;
    }

    private boolean isActiveApproval(WorkflowNodeInstance node) {
        return node != null
                && node.getNodeStatus() == WorkflowNodeStatus.ACTIVE
                && node.getNodeType() == WorkflowNodeType.APPROVAL;
    }

    private boolean shouldMarkWarned(WorkflowNodeInstance node, Instant now) {
        return positive(node.getWarningDurationMinutes())
                && status(node) == WorkflowOvertimeStatus.NORMAL
                && node.getWarnedAt() == null
                && node.getOverdueAt() == null
                && !now.isBefore(node.getActivatedAt().plusSeconds(node.getWarningDurationMinutes() * 60L));
    }

    private boolean shouldMarkOverdue(WorkflowNodeInstance node, Instant now) {
        return positive(node.getOvertimeDurationMinutes())
                && status(node) != WorkflowOvertimeStatus.OVERDUE
                && node.getOverdueAt() == null
                && !now.isBefore(node.getActivatedAt().plusSeconds(node.getOvertimeDurationMinutes() * 60L));
    }

    private boolean positive(Integer value) {
        return value != null && value > 0;
    }

    private int markWarned(WorkflowNodeInstance node, Instant now) {
        WorkflowOvertimeStatus previousStatus = status(node);
        node.setOvertimeStatus(WorkflowOvertimeStatus.WARNED);
        node.setWarnedAt(now);
        if (!updateNodeIfCurrent(node, now)) {
            return 0;
        }
        WorkflowInstance instance = instanceDao.findById(node.getInstanceId());
        if (instance != null) {
            eventWriter.insert(eventFactory.overtimeWarned(instance, node, SYSTEM_OPERATOR,
                    payload(node, node.getWarningDurationMinutes(), now, previousStatus, WorkflowOvertimeStatus.WARNED),
                    now));
        }
        return 1;
    }

    private int markOverdue(WorkflowNodeInstance node, Instant now) {
        WorkflowOvertimeStatus previousStatus = status(node);
        node.setOvertimeStatus(WorkflowOvertimeStatus.OVERDUE);
        node.setOverdueAt(now);
        if (!updateNodeIfCurrent(node, now)) {
            return 0;
        }
        WorkflowInstance instance = instanceDao.findById(node.getInstanceId());
        if (instance != null) {
            eventWriter.insert(eventFactory.overtimeOverdue(instance, node, SYSTEM_OPERATOR,
                    payload(node, node.getOvertimeDurationMinutes(), now, previousStatus, WorkflowOvertimeStatus.OVERDUE),
                    now));
        }
        return 1;
    }

    private boolean updateNodeIfCurrent(WorkflowNodeInstance node, Instant now) {
        Integer expectedVersion = node.getVersion();
        EntityLifecycle.prepareUpdate(node, now, EntityLifecycle.nextVersion(expectedVersion));
        return nodeDao.updateByIdAndVersion(node, expectedVersion) > 0;
    }

    private WorkflowOvertimeStatus status(WorkflowNodeInstance node) {
        return node.getOvertimeStatus() == null ? WorkflowOvertimeStatus.NORMAL : node.getOvertimeStatus();
    }

    private String payload(WorkflowNodeInstance node,
                           Integer triggerMinutes,
                           Instant triggeredAt,
                           WorkflowOvertimeStatus previousStatus,
                           WorkflowOvertimeStatus currentStatus) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("nodeInstanceId", node.getId());
        detail.put("nodeKey", node.getNodeKey());
        detail.put("triggerMinutes", triggerMinutes);
        detail.put("triggeredAt", triggeredAt == null ? null : triggeredAt.toString());
        detail.put("previousStatus", previousStatus == null ? null : previousStatus.getCode());
        detail.put("currentStatus", currentStatus.getCode());
        try {
            return OBJECT_MAPPER.writeValueAsString(detail);
        } catch (JsonProcessingException ex) {
            throw new PlatformException("workflow overtime payload serialize failed: " + node.getId(), ex);
        }
    }
}
