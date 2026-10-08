package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class WorkflowTaskActionAvailabilityService {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final WorkflowTaskDao taskDao;
    private final WorkflowInstanceDao instanceDao;
    private final WorkflowNodeInstanceDao nodeDao;
    private final WorkflowRouteInstanceDao routeDao;
    private final WorkflowTaskAssignmentPolicyService assignmentPolicyService;
    private final WorkflowUserTitleResolver userTitleResolver;

    public WorkflowTaskActionAvailabilityService(WorkflowTaskDao taskDao,
                                                 WorkflowInstanceDao instanceDao,
                                                 WorkflowNodeInstanceDao nodeDao) {
        this(taskDao, instanceDao, nodeDao, null, new WorkflowTaskAssignmentPolicyService(),
                WorkflowUserTitleResolver.NONE);
    }

    @Autowired
    public WorkflowTaskActionAvailabilityService(WorkflowTaskDao taskDao,
                                                 WorkflowInstanceDao instanceDao,
                                                 WorkflowNodeInstanceDao nodeDao,
                                                 WorkflowRouteInstanceDao routeDao,
                                                 WorkflowTaskAssignmentPolicyService assignmentPolicyService,
                                                 ObjectProvider<WorkflowUserTitleResolver> userTitleResolver) {
        this(taskDao, instanceDao, nodeDao, routeDao, assignmentPolicyService, userTitleResolver == null
                ? WorkflowUserTitleResolver.NONE
                : userTitleResolver.getIfAvailable(() -> WorkflowUserTitleResolver.NONE));
    }

    public WorkflowTaskActionAvailabilityService(WorkflowTaskDao taskDao,
                                                 WorkflowInstanceDao instanceDao,
                                                 WorkflowNodeInstanceDao nodeDao,
                                                 WorkflowRouteInstanceDao routeDao,
                                                 WorkflowTaskAssignmentPolicyService assignmentPolicyService,
                                                 WorkflowUserTitleResolver userTitleResolver) {
        this.taskDao = taskDao;
        this.instanceDao = instanceDao;
        this.nodeDao = nodeDao;
        this.routeDao = routeDao;
        this.assignmentPolicyService = assignmentPolicyService == null
                ? new WorkflowTaskAssignmentPolicyService()
                : assignmentPolicyService;
        this.userTitleResolver = userTitleResolver == null ? WorkflowUserTitleResolver.NONE : userTitleResolver;
    }

    public List<WorkflowTaskAvailableAction> availableActions(String taskId, String operatorId) {
        WorkflowTask task = requireTask(taskId);
        WorkflowInstance instance = requireInstance(task);
        WorkflowNodeInstance node = task.getNodeInstanceId() == null ? null : nodeDao.findById(task.getNodeInstanceId());
        if (task.getTaskStatus() == WorkflowTaskStatus.DONE && routeDao != null
                && WorkflowApprovalRevocationPolicy.allowed(task, instance, node,
                nodeDao.query(Criteria.of().eq("instanceId", instance.getId()), ALL),
                routeDao.query(Criteria.of().eq("instanceId", instance.getId()), ALL),
                taskDao.query(Criteria.of().eq("instanceId", instance.getId()), ALL), operatorId))
            return List.of(WorkflowTaskAvailableAction.of("revokeApprove", "撤销审批意见").requireReason(true).withTask(task, node, userTitles(task)));
        if (task.getTaskStatus() != WorkflowTaskStatus.TODO || !assignmentPolicyService.canProcess(task, operatorId)) return List.of();
        if (task.getTaskKind() != WorkflowTaskKind.NOTICE && task.getTaskKind() != WorkflowTaskKind.RESUBMIT
                && (instance.getInstanceStatus() != WorkflowInstanceStatus.RUNNING || node == null
                || node.getNodeStatus() != WorkflowNodeStatus.ACTIVE)) return List.of();
        List<WorkflowTaskAvailableAction> actions = new ArrayList<>();
        if (task.getTaskKind() == WorkflowTaskKind.APPROVAL) {
            actions.add(WorkflowTaskAvailableAction.of("approve", "通过"));
            if (node != null && !Boolean.FALSE.equals(node.getAllowReject())) {
                actions.add(WorkflowTaskAvailableAction.of("reject", "驳回")
                        .requireReason(Boolean.TRUE.equals(node.getRequireRejectReason()))
                        .supportRejectReturnToMe(Boolean.TRUE.equals(node.getAllowRejectReturnToMe())));
            }
            if (instance.getInstanceStatus() == WorkflowInstanceStatus.RUNNING
                    && node != null
                    && Boolean.TRUE.equals(node.getAllowRollback())) {
                actions.add(WorkflowTaskAvailableAction.of("rollback", "回退")
                        .requireReason(Boolean.TRUE.equals(node.getRequireRollbackReason())));
            }
            if (instance.getInstanceStatus() == WorkflowInstanceStatus.RUNNING
                    && node != null
                    && node.getNodeType() == WorkflowNodeType.APPROVAL
                    && Boolean.TRUE.equals(node.getAllowAddSign())
                    && !hasMultipleTodoApprovalTasks(task)
                    && hasOutgoingCandidateRoute(instance.getId(), node.getNodeKey())) {
                actions.add(WorkflowTaskAvailableAction.of("addSign", "加签")
                        .requireReason(true));
            }
        } else if (task.getTaskKind() == WorkflowTaskKind.BUSINESS) {
            actions.add(WorkflowTaskAvailableAction.of("complete", "完成"));
        } else if (task.getTaskKind() == WorkflowTaskKind.NOTICE) {
            actions.add(WorkflowTaskAvailableAction.of("notice", "已阅"));
        } else if (task.getTaskKind() == WorkflowTaskKind.RESUBMIT
                && instance.getInstanceStatus() == WorkflowInstanceStatus.REJECTED
                && instance.getRejectResubmitMode() == WorkflowRejectResubmitMode.RETURN_TO_ME) {
            actions.add(WorkflowTaskAvailableAction.of("resubmit", "重提"));
        }
        if (task.getTaskKind() != WorkflowTaskKind.RESUBMIT) {
            actions.add(WorkflowTaskAvailableAction.of("transfer", "转办").requireTargetAssignee());
        }
        return actions.stream().map(action -> action.withTask(task, node, userTitles(task))).toList();
    }

    private Map<String, String> userTitles(WorkflowTask task) {
        Set<String> userIds = new LinkedHashSet<>();
        addUserId(userIds, task.getAssigneeId());
        addUserId(userIds, task.getOriginalAssigneeId());
        addUserId(userIds, task.getDelegatedFromUserId());
        addUserId(userIds, task.getDelegatedToUserId());
        Map<String, String> titles = userTitleResolver.titles(userIds);
        return titles == null ? Map.of() : titles;
    }

    private void addUserId(Set<String> userIds, String userId) {
        if (userId != null && !userId.isBlank()) {
            userIds.add(userId);
        }
    }

    private WorkflowTask requireTask(String taskId) {
        String validTaskId = requireText(taskId, "workflow task id must not be blank");
        WorkflowTask task = WorkflowTenantScope.visible(taskDao.findById(validTaskId));
        if (task == null) {
            throw new PlatformException("workflow task not found: " + validTaskId);
        }
        return task;
    }

    private WorkflowInstance requireInstance(WorkflowTask task) {
        WorkflowInstance instance = instanceDao.findById(task.getInstanceId());
        if (instance == null) {
            throw new PlatformException("workflow instance not found: " + task.getInstanceId());
        }
        return instance;
    }

    private boolean hasMultipleTodoApprovalTasks(WorkflowTask task) {
        return taskDao.query(Criteria.of()
                        .eq("instanceId", task.getInstanceId())
                        .eq("nodeInstanceId", task.getNodeInstanceId()), ALL).stream()
                .filter(nodeTask -> nodeTask.getTaskKind() == WorkflowTaskKind.APPROVAL)
                .filter(nodeTask -> nodeTask.getTaskStatus() == WorkflowTaskStatus.TODO)
                .count() > 1;
    }

    private boolean hasOutgoingCandidateRoute(String instanceId, String nodeKey) {
        if (routeDao == null || nodeKey == null || nodeKey.isBlank()) {
            return false;
        }
        return routeDao.query(Criteria.of()
                        .eq("instanceId", instanceId)
                        .eq("sourceNodeKey", nodeKey), ALL).stream()
                .anyMatch(route -> route.getRouteStatus() == WorkflowRouteStatus.CANDIDATE);
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new PlatformException(message);
        }
        return value;
    }
}
