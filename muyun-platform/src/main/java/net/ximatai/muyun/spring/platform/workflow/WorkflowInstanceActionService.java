package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class WorkflowInstanceActionService {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final WorkflowInstanceDao instanceDao;
    private final WorkflowNodeInstanceDao nodeDao;
    private final WorkflowRouteInstanceDao routeDao;
    private final WorkflowTaskDao taskDao;
    private final WorkflowEventDao eventDao;
    private final WorkflowRuntimeEventFactory eventFactory;
    private final WorkflowArchiveService archiveService;
    private final WorkflowActionPolicyService actionPolicyService;
    private final Optional<WorkflowApprovalSummaryWriter> approvalSummaryWriter;
    private final WorkflowRuntimePluginDispatcher pluginDispatcher;

    public WorkflowInstanceActionService(WorkflowInstanceDao instanceDao,
                                         WorkflowNodeInstanceDao nodeDao,
                                         WorkflowRouteInstanceDao routeDao,
                                         WorkflowTaskDao taskDao,
                                         WorkflowEventDao eventDao,
                                         WorkflowRuntimeEventFactory eventFactory,
                                         WorkflowArchiveService archiveService,
                                         Optional<WorkflowApprovalSummaryWriter> approvalSummaryWriter) {
        this(instanceDao, nodeDao, routeDao, taskDao, eventDao, eventFactory, archiveService, new WorkflowActionPolicyService(),
                approvalSummaryWriter, null);
    }

    public WorkflowInstanceActionService(WorkflowInstanceDao instanceDao,
                                         WorkflowNodeInstanceDao nodeDao,
                                         WorkflowRouteInstanceDao routeDao,
                                         WorkflowTaskDao taskDao,
                                         WorkflowEventDao eventDao,
                                         WorkflowRuntimeEventFactory eventFactory,
                                         WorkflowArchiveService archiveService,
                                         WorkflowActionPolicyService actionPolicyService,
                                         Optional<WorkflowApprovalSummaryWriter> approvalSummaryWriter) {
        this(instanceDao, nodeDao, routeDao, taskDao, eventDao, eventFactory, archiveService, actionPolicyService,
                approvalSummaryWriter, null);
    }

    @Autowired
    public WorkflowInstanceActionService(WorkflowInstanceDao instanceDao,
                                         WorkflowNodeInstanceDao nodeDao,
                                         WorkflowRouteInstanceDao routeDao,
                                         WorkflowTaskDao taskDao,
                                         WorkflowEventDao eventDao,
                                         WorkflowRuntimeEventFactory eventFactory,
                                         WorkflowArchiveService archiveService,
                                         WorkflowActionPolicyService actionPolicyService,
                                         Optional<WorkflowApprovalSummaryWriter> approvalSummaryWriter,
                                         WorkflowRuntimePluginDispatcher pluginDispatcher) {
        this.instanceDao = instanceDao;
        this.nodeDao = nodeDao;
        this.routeDao = routeDao;
        this.taskDao = taskDao;
        this.eventDao = eventDao;
        this.eventFactory = eventFactory;
        this.archiveService = archiveService;
        this.actionPolicyService = actionPolicyService == null ? new WorkflowActionPolicyService() : actionPolicyService;
        this.approvalSummaryWriter = approvalSummaryWriter == null ? Optional.empty() : approvalSummaryWriter;
        this.pluginDispatcher = pluginDispatcher == null ? new WorkflowRuntimePluginDispatcher(List.of()) : pluginDispatcher;
    }

    @Transactional
    public WorkflowInstanceActionResult revoke(WorkflowInstanceActionRequest request) {
        WorkflowInstanceActionResult result = closeInstance(request, WorkflowInstanceStatus.REVOKED,
                WorkflowApprovalStatus.REVOKED, WorkflowTaskStatus.CANCELED, WorkflowNodeStatus.CANCELED, "revoke");
        archiveAndReleaseApproval(result.instance(), WorkflowArchiveReason.RECALLED,
                result.instance().getLastOperatedAt());
        return result;
    }

    @Transactional
    public WorkflowInstanceActionResult reset(WorkflowInstanceActionRequest request) {
        return reset(request, false);
    }

    @Transactional
    public WorkflowInstanceActionResult managementReset(WorkflowInstanceActionRequest request) {
        return reset(request, true);
    }

    private WorkflowInstanceActionResult reset(WorkflowInstanceActionRequest request, boolean management) {
        WorkflowInstance instance = requireExistingInstance(request);
        Instant now = operatedAt(request);
        String operatorId = operatorId(request);
        if (management) {
            actionPolicyService.requireManagementInstanceAction(WorkflowActionPolicyService.MANAGEMENT_RESET_ACTION,
                    request.reason());
        } else {
            actionPolicyService.requireRuntimeAction(instance, "reset");
            actionPolicyService.requireInstanceAction("reset", request.reason());
        }
        List<WorkflowTask> tasks = runningTasks(instance.getId());
        List<WorkflowNodeInstance> nodes = runningNodes(instance.getId());
        List<WorkflowRouteInstance> routes = openRoutes(instance.getId());
        dispatchInstanceAction(instance, nodes, tasks, WorkflowRuntimePluginEventType.BEFORE_RESET, "reset",
                operatorId, null, request.reason());

        instance.setLastActionCode("reset");
        instance.setLastActionReason(request.reason());
        instance.setLastOperatorId(operatorId);
        instance.setLastOperatedAt(now);
        updateInstance(instance, now);

        for (WorkflowTask task : tasks) {
            task.setTaskStatus(WorkflowTaskStatus.CANCELED);
            task.setActualProcessorId(operatorId);
            task.setDecision("reset");
            task.setResultMessage(request.reason());
            task.setCompletedAt(now);
            updateTask(task, now);
        }
        for (WorkflowNodeInstance node : nodes) {
            node.setNodeStatus(WorkflowNodeStatus.CANCELED);
            node.setCompletedAt(now);
            updateNode(node, now);
        }
        for (WorkflowRouteInstance route : routes) {
            route.setRouteStatus(WorkflowRouteStatus.CANCELED);
            route.setClosedReason("reset");
            route.setInvalidatedByActionId("reset");
            route.setInvalidatedAt(now);
            updateRoute(route, now);
        }

        WorkflowEvent event = eventFactory.instanceReset(instance, operatorId, request.reason(), now);
        EntityLifecycle.prepareInsert(event, now);
        eventDao.insert(event);
        WorkflowMutationFacts.recordChanged(instance);
        dispatchInstanceAction(instance, nodes, tasks, WorkflowRuntimePluginEventType.AFTER_RESET, "reset",
                operatorId, null, request.reason());
        archiveAndReleaseApproval(instance, WorkflowArchiveReason.RESET, now, management);
        return new WorkflowInstanceActionResult(instance, tasks, nodes, routes, event);
    }

    @Transactional
    public WorkflowInstanceActionResult terminate(WorkflowInstanceActionRequest request) {
        return closeInstance(request, WorkflowInstanceStatus.TERMINATED, WorkflowApprovalStatus.TERMINATED,
                WorkflowTaskStatus.INVALIDATED, WorkflowNodeStatus.CANCELED, "terminate");
    }

    @Transactional
    public WorkflowInstanceActionResult forceTerminate(WorkflowInstanceActionRequest request) {
        return closeInstance(request, WorkflowInstanceStatus.TERMINATED, WorkflowApprovalStatus.TERMINATED,
                WorkflowTaskStatus.INVALIDATED, WorkflowNodeStatus.CANCELED, "forceTerminate");
    }

    private WorkflowInstanceActionResult closeInstance(WorkflowInstanceActionRequest request,
                                                       WorkflowInstanceStatus instanceStatus,
                                                       WorkflowApprovalStatus approvalStatus,
                                                       WorkflowTaskStatus taskStatus,
                                                       WorkflowNodeStatus nodeStatus,
                                                       String actionCode) {
        WorkflowInstance instance = requireRunningInstance(request);
        Instant now = operatedAt(request);
        String operatorId = operatorId(request);
        if ("forceTerminate".equals(actionCode)) {
            actionPolicyService.requireManagementInstanceAction(actionCode, request.reason());
        } else {
            actionPolicyService.requireRuntimeAction(instance, actionCode);
            actionPolicyService.requireInstanceAction(actionCode, request.reason());
        }
        List<WorkflowTask> tasks = runningTasks(instance.getId());
        List<WorkflowNodeInstance> nodes = runningNodes(instance.getId());
        List<WorkflowRouteInstance> routes = openRoutes(instance.getId());
        WorkflowRuntimeTerminateMode terminateMode = "forceTerminate".equals(actionCode)
                ? WorkflowRuntimeTerminateMode.FORCE : WorkflowRuntimeTerminateMode.NORMAL;
        dispatchInstanceAction(instance, nodes, tasks, beforeEvent(actionCode), actionCode, operatorId,
                terminateMode, request.reason());

        instance.setInstanceStatus(instanceStatus);
        if (Boolean.TRUE.equals(instance.getApprovalEnabled())) {
            instance.setApprovalStatus(approvalStatus);
        }
        instance.setTerminatedAt(instanceStatus == WorkflowInstanceStatus.TERMINATED ? now : instance.getTerminatedAt());
        instance.setLastActionCode(actionCode);
        instance.setLastActionReason(request.reason());
        instance.setLastOperatorId(operatorId);
        instance.setLastOperatedAt(now);
        updateInstance(instance, now);

        for (WorkflowTask task : tasks) {
            task.setTaskStatus(taskStatus);
            task.setActualProcessorId(operatorId);
            task.setDecision(actionCode);
            task.setResultMessage(request.reason());
            task.setCompletedAt(now);
            updateTask(task, now);
        }
        for (WorkflowNodeInstance node : nodes) {
            node.setNodeStatus(nodeStatus);
            node.setCompletedAt(now);
            updateNode(node, now);
        }
        for (WorkflowRouteInstance route : routes) {
            route.setRouteStatus(WorkflowRouteStatus.CANCELED);
            route.setClosedReason(actionCode);
            route.setInvalidatedByActionId(actionCode);
            route.setInvalidatedAt(now);
            updateRoute(route, now);
        }

        WorkflowEvent event = switch (actionCode) {
            case "revoke" -> eventFactory.instanceRevoked(instance, operatorId, request.reason(), now);
            case "forceTerminate" -> eventFactory.instanceForceTerminated(instance, operatorId, request.reason(), now);
            default -> eventFactory.instanceTerminated(instance, operatorId, request.reason(), now);
        };
        EntityLifecycle.prepareInsert(event, now);
        eventDao.insert(event);
        WorkflowMutationFacts.recordChanged(instance);
        dispatchInstanceAction(instance, nodes, tasks, afterEvent(actionCode), actionCode, operatorId,
                terminateMode, request.reason());
        if (!"revoke".equals(actionCode)) {
            writeApprovalSummary(instance, "forceTerminate".equals(actionCode));
        }
        return new WorkflowInstanceActionResult(instance, tasks, nodes, routes, event);
    }

    private WorkflowRuntimePluginEventType beforeEvent(String actionCode) {
        return switch (actionCode) {
            case "revoke" -> WorkflowRuntimePluginEventType.BEFORE_REVOKE;
            case "reset" -> WorkflowRuntimePluginEventType.BEFORE_RESET;
            default -> WorkflowRuntimePluginEventType.BEFORE_TERMINATE;
        };
    }

    private WorkflowRuntimePluginEventType afterEvent(String actionCode) {
        return switch (actionCode) {
            case "revoke" -> WorkflowRuntimePluginEventType.AFTER_REVOKE;
            case "reset" -> WorkflowRuntimePluginEventType.AFTER_RESET;
            default -> WorkflowRuntimePluginEventType.AFTER_TERMINATE;
        };
    }

    private void dispatchInstanceAction(WorkflowInstance instance,
                                        List<WorkflowNodeInstance> nodes,
                                        List<WorkflowTask> tasks,
                                        WorkflowRuntimePluginEventType eventType,
                                        String actionCode,
                                        String operatorId,
                                        WorkflowRuntimeTerminateMode terminateMode,
                                        String reason) {
        Map<String, WorkflowNodeInstance> nodesById = new LinkedHashMap<>();
        if (nodes != null) {
            nodes.forEach(node -> nodesById.put(node.getId(), node));
        }
        if (tasks != null && !tasks.isEmpty()) {
            tasks.forEach(task -> dispatch(instance, nodesById.get(task.getNodeInstanceId()), task, eventType,
                    actionCode, operatorId, terminateMode, reason));
            return;
        }
        if (nodes != null && !nodes.isEmpty()) {
            nodes.forEach(node -> dispatch(instance, node, null, eventType, actionCode, operatorId,
                    terminateMode, reason));
            return;
        }
        dispatch(instance, null, null, eventType, actionCode, operatorId, terminateMode, reason);
    }

    private void dispatch(WorkflowInstance instance,
                          WorkflowNodeInstance node,
                          WorkflowTask task,
                          WorkflowRuntimePluginEventType eventType,
                          String actionCode,
                          String operatorId,
                          WorkflowRuntimeTerminateMode terminateMode,
                          String reason) {
        pluginDispatcher.dispatch(new WorkflowRuntimePluginContext(eventType, actionCode,
                instance == null ? null : instance.getModuleAlias(),
                instance == null ? null : instance.getRecordId(),
                instance == null ? null : instance.getId(),
                node == null ? null : node.getNodeKey(),
                task == null ? null : task.getId(),
                operatorId, null, null, terminateMode, reason, instance, node, task));
    }

    private WorkflowInstance requireRunningInstance(WorkflowInstanceActionRequest request) {
        WorkflowInstance instance = requireExistingInstance(request);
        if (instance.getInstanceStatus() != WorkflowInstanceStatus.RUNNING) {
            throw new PlatformException("workflow instance is not running: " + instance.getId());
        }
        return instance;
    }

    private WorkflowInstance requireExistingInstance(WorkflowInstanceActionRequest request) {
        String instanceId = requireText(request == null ? null : request.instanceId(),
                "workflow instance id must not be blank");
        WorkflowMutationLock.instance(instanceId);
        WorkflowInstance instance = WorkflowTenantScope.visible(instanceDao.findById(instanceId));
        if (instance == null) {
            throw new PlatformException("workflow instance not found: " + instanceId);
        }
        return instance;
    }

    private List<WorkflowTask> runningTasks(String instanceId) {
        return taskDao.query(Criteria.of()
                .eq("instanceId", instanceId)
                .eq("taskStatus", WorkflowTaskStatus.TODO), ALL);
    }

    private List<WorkflowNodeInstance> runningNodes(String instanceId) {
        return nodeDao.query(Criteria.of()
                .eq("instanceId", instanceId)
                .eq("nodeStatus", WorkflowNodeStatus.ACTIVE), ALL);
    }

    private List<WorkflowRouteInstance> openRoutes(String instanceId) {
        return routeDao.query(Criteria.of()
                .eq("instanceId", instanceId)
                .in("routeStatus", List.of(WorkflowRouteStatus.CANDIDATE, WorkflowRouteStatus.EFFECTIVE)), ALL);
    }

    private void updateInstance(WorkflowInstance instance, Instant now) {
        Integer expectedVersion = instance.getVersion();
        EntityLifecycle.prepareUpdate(instance, now, EntityLifecycle.nextVersion(expectedVersion));
        int updated = instanceDao.updateByIdAndVersion(instance, expectedVersion);
        if (updated <= 0) {
            throw new OptimisticLockException("workflow instance version conflict: " + instance.getId());
        }
    }

    private void updateTask(WorkflowTask task, Instant now) {
        Integer expectedVersion = task.getVersion();
        EntityLifecycle.prepareUpdate(task, now, EntityLifecycle.nextVersion(expectedVersion));
        int updated = taskDao.updateByIdAndVersion(task, expectedVersion);
        if (updated <= 0) {
            throw new OptimisticLockException("workflow task version conflict: " + task.getId());
        }
    }

    private void updateNode(WorkflowNodeInstance node, Instant now) {
        Integer expectedVersion = node.getVersion();
        EntityLifecycle.prepareUpdate(node, now, EntityLifecycle.nextVersion(expectedVersion));
        int updated = nodeDao.updateByIdAndVersion(node, expectedVersion);
        if (updated <= 0) {
            throw new OptimisticLockException("workflow node version conflict: " + node.getId());
        }
    }

    private void updateRoute(WorkflowRouteInstance route, Instant now) {
        Integer expectedVersion = route.getVersion();
        EntityLifecycle.prepareUpdate(route, now, EntityLifecycle.nextVersion(expectedVersion));
        int updated = routeDao.updateByIdAndVersion(route, expectedVersion);
        if (updated <= 0) {
            throw new OptimisticLockException("workflow route version conflict: " + route.getId());
        }
    }

    private void writeApprovalSummary(WorkflowInstance instance, boolean allowMissingRecord) {
        if (!Boolean.TRUE.equals(instance.getApprovalEnabled())) {
            return;
        }
        String actionCode = "resubmit_return_to_me".equals(instance.getLastActionCode()) ? "resubmit" : instance.getLastActionCode();
        WorkflowApprovalMutationScope.run(instance.getModuleAlias(), instance.getRecordId(), actionCode,
                () -> approvalSummaryWriter.ifPresent(writer -> {
                    var summary = new WorkflowApprovalSummary(
                instance.getTenantId(),
                instance.getModuleAlias(),
                instance.getRecordId(),
                instance.getId(),
                instance.getApprovalStatus(),
                instance.getStartedBy(),
                instance.getStartedAt(),
                instance.getApprovalCompletedAt());
                    if (allowMissingRecord) writer.writeSubmittedIfPresent(summary);
                    else writer.writeSubmitted(summary);
                }));
    }

    private void archiveAndReleaseApproval(WorkflowInstance instance, WorkflowArchiveReason archiveReason,
                                           Instant archivedAt) {
        archiveAndReleaseApproval(instance, archiveReason, archivedAt, false);
    }

    private void archiveAndReleaseApproval(WorkflowInstance instance, WorkflowArchiveReason archiveReason,
                                           Instant archivedAt, boolean allowMissingRecord) {
        archiveService.archiveCurrentInstance(instance, archiveReason, archivedAt);
        if (Boolean.TRUE.equals(instance.getApprovalEnabled())) {
            WorkflowApprovalMutationScope.run(instance.getModuleAlias(), instance.getRecordId(), instance.getLastActionCode(),
                    () -> approvalSummaryWriter.ifPresent(writer -> {
                        if (allowMissingRecord) writer.clearCurrentIfPresent(instance.getTenantId(), instance.getModuleAlias(), instance.getRecordId());
                        else writer.clearCurrent(instance.getTenantId(), instance.getModuleAlias(), instance.getRecordId());
                    }));
        }
    }

    private String operatorId(WorkflowInstanceActionRequest request) {
        if (request.operatorId() != null && !request.operatorId().isBlank()) {
            return request.operatorId();
        }
        return CurrentUserContext.currentUser()
                .map(user -> user.userId())
                .filter(userId -> !userId.isBlank())
                .orElse("system");
    }

    private Instant operatedAt(WorkflowInstanceActionRequest request) {
        return request.operatedAt() == null ? Instant.now() : request.operatedAt();
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new PlatformException(message);
        }
        return value;
    }
}
