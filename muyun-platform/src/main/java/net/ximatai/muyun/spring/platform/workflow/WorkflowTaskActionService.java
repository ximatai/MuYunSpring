package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.id.Ids;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class WorkflowTaskActionService {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final WorkflowTaskDao taskDao;
    private final WorkflowInstanceDao instanceDao;
    private final WorkflowNodeInstanceDao nodeInstanceDao;
    private final WorkflowRouteInstanceDao routeInstanceDao;
    private final WorkflowEventDao eventDao;
    private final WorkflowRuntimeEventFactory eventFactory;
    private final WorkflowApprovalTaskPolicyService approvalTaskPolicyService;
    private final WorkflowActionPolicyService actionPolicyService;
    private final WorkflowRuntimeProgressionService progressionService;
    private final Optional<WorkflowApprovalSummaryWriter> approvalSummaryWriter;
    private final WorkflowDelegationService delegationService;
    private final WorkflowDelegationCompletionNoticeService delegationCompletionNoticeService;
    private final WorkflowRuntimePluginDispatcher pluginDispatcher;
    private final WorkflowModuleTaskEvaluator taskEvaluator;
    private final WorkflowBusinessTaskResolver taskSpecifications;
    private final WorkflowTaskCheckResultDao taskCheckResults;

    private final ObjectProvider<WorkflowAutomaticApprovalService> automaticApprovals;

    @Autowired
    public WorkflowTaskActionService(WorkflowTaskDao taskDao,
                                     WorkflowInstanceDao instanceDao,
                                     WorkflowNodeInstanceDao nodeInstanceDao,
                                     WorkflowRouteInstanceDao routeInstanceDao,
                                     WorkflowEventDao eventDao,
                                     WorkflowRuntimeEventFactory eventFactory,
                                     WorkflowApprovalTaskPolicyService approvalTaskPolicyService,
                                     WorkflowActionPolicyService actionPolicyService,
                                     WorkflowRuntimeProgressionService progressionService,
                                     Optional<WorkflowApprovalSummaryWriter> approvalSummaryWriter,
                                     WorkflowDelegationService delegationService,
                                     WorkflowDelegationCompletionNoticeService delegationCompletionNoticeService,
                                     WorkflowRuntimePluginDispatcher pluginDispatcher,
                                     WorkflowModuleTaskEvaluator taskEvaluator,
                                     WorkflowBusinessTaskResolver taskSpecifications,
                                     WorkflowTaskCheckResultDao taskCheckResults,
                                     ObjectProvider<WorkflowAutomaticApprovalService> automaticApprovals) {
        this.automaticApprovals = java.util.Objects.requireNonNull(automaticApprovals, "automaticApprovals");
        this.taskEvaluator = java.util.Objects.requireNonNull(taskEvaluator, "taskEvaluator");
        this.taskSpecifications = java.util.Objects.requireNonNull(taskSpecifications, "taskSpecifications");
        this.taskCheckResults = java.util.Objects.requireNonNull(taskCheckResults, "taskCheckResults");
        this.taskDao = taskDao;
        this.instanceDao = instanceDao;
        this.nodeInstanceDao = nodeInstanceDao;
        this.routeInstanceDao = routeInstanceDao;
        this.eventDao = eventDao;
        this.eventFactory = eventFactory;
        this.approvalTaskPolicyService = approvalTaskPolicyService;
        this.actionPolicyService = actionPolicyService == null ? new WorkflowActionPolicyService() : actionPolicyService;
        this.progressionService = progressionService;
        this.approvalSummaryWriter = approvalSummaryWriter == null ? Optional.empty() : approvalSummaryWriter;
        this.delegationService = delegationService;
        this.delegationCompletionNoticeService = delegationCompletionNoticeService;
        this.pluginDispatcher = pluginDispatcher == null ? new WorkflowRuntimePluginDispatcher(List.of()) : pluginDispatcher;
    }

    @Transactional
    public WorkflowTaskActionResult approve(WorkflowTaskActionRequest request) {
        return approveInternal(request, false);
    }

    @Transactional
    public WorkflowTaskActionResult approveAutomatically(WorkflowTaskActionRequest request) {
        return approveInternal(request, true);
    }

    private WorkflowTaskActionResult approveInternal(WorkflowTaskActionRequest request, boolean automatic) {
        WorkflowTask task = requireTodoTask(request);
        if (task.getTaskKind() != WorkflowTaskKind.APPROVAL) {
            throw new PlatformException("workflow task is not an approval task: " + request.taskId());
        }
        WorkflowInstance instance = requireInstance(task);
        WorkflowNodeInstance node = requireNode(task);
        Instant now = operatedAt(request);
        String operatorId = operatorId(request);
        actionPolicyService.requireRuntimeAction(instance, "approve");
        actionPolicyService.requireTaskOperator(task, "approve", operatorId);
        dispatchTask(instance, node, task, WorkflowRuntimePluginEventType.BEFORE_APPROVE, "approve",
                operatorId, null, null, request.reason());
        task.setTaskStatus(WorkflowTaskStatus.DONE);
        task.setActualProcessorId(operatorId);
        task.setDecision(automatic ? "auto_approve" : "approve");
        task.setResultMessage(request.reason());
        task.setCompletedAt(now);
        updateTask(task, now);

        List<WorkflowTask> nodeTasks = nodeTasks(task);
        List<WorkflowTask> effectiveTasks = replaceTask(nodeTasks, task);
        if (approvalTaskPolicyService.isNodePassed(node.getApprovalMode(), node.getApprovalRatio(), effectiveTasks)) {
            node.setNodeStatus(WorkflowNodeStatus.COMPLETED);
            node.setApprovedTaskCount(countStatus(effectiveTasks, WorkflowTaskStatus.DONE));
            node.setCompletedTaskCount(countCompleted(effectiveTasks));
            node.setCompletedAt(now);
            updateNode(node, now);
            if (approvalTaskPolicyService.shouldSkipPendingSiblings(node.getApprovalMode(), node.getApprovalRatio(),
                    effectiveTasks)) {
                skipPendingSiblings(instance, task, effectiveTasks, operatorId, now);
            }
        }
        WorkflowEvent event = eventFactory.taskCompleted(instance, task, automatic ? "auto_approve" : "approve", operatorId, request.reason(), now);
        eventDao.insert(event);
        createDelegationCompletionNotice(instance, task, operatorId, now);
        if (node.getNodeStatus() == WorkflowNodeStatus.COMPLETED) {
            progressionService.advanceFromNode(instance.getId(), node.getNodeKey(), operatorId, now,
                    request.selectedRouteKey(), request.selectedReason(), request.manualRouteSelections());
        }
        dispatchTask(instance, node, task, WorkflowRuntimePluginEventType.AFTER_APPROVE, "approve",
                operatorId, null, null, request.reason());
        automaticApprovals.getObject().continueFor(instance.getId(), operatorId, now);
        return WorkflowTaskActionResult.of(task, node, instance, event);
    }

    @Transactional
    public WorkflowTaskActionResult revokeApprove(WorkflowTaskActionRequest request) {
        WorkflowTask task = WorkflowMutationLock.task(taskDao, requireText(request == null ? null : request.taskId(), "审批任务不能为空"));
        if (task == null) throw new PlatformException("审批任务不存在");
        WorkflowInstance instance = requireInstance(task);
        WorkflowNodeInstance node = requireNode(task);
        String operator = operatorId(request);
        var nodes = nodeInstanceDao.query(Criteria.of().eq("instanceId", instance.getId()), ALL);
        var routes = requireRouteDao().query(Criteria.of().eq("instanceId", instance.getId()), ALL);
        var tasks = taskDao.query(Criteria.of().eq("instanceId", instance.getId()), ALL);
        if (!WorkflowApprovalRevocationPolicy.allowed(task, instance, node, nodes, routes, tasks, operator))
            throw new PlatformException("审批意见已不能撤销：下游已办理、已跨越分支或审批已完成");
        requireText(request.reason(), "撤销审批必须填写原因");
        actionPolicyService.requireRuntimeAction(instance, "revokeApprove");
        Instant now = operatedAt(request);
        var skippedVotes = node.getNodeStatus() == WorkflowNodeStatus.COMPLETED
                && (node.getApprovalMode() == null || node.getApprovalMode() == WorkflowApprovalMode.ANY || node.getApprovalMode() == WorkflowApprovalMode.RATIO)
                ? tasks.stream().filter(item -> node.getId().equals(item.getNodeInstanceId())
                && item.getTaskKind() == WorkflowTaskKind.APPROVAL && item.getTaskStatus() == WorkflowTaskStatus.SKIPPED
                && "skip".equals(item.getDecision()) && node.getCompletedAt() != null
                && node.getCompletedAt().equals(item.getCompletedAt())).toList() : List.<WorkflowTask>of();
        dispatchTask(instance, node, task, WorkflowRuntimePluginEventType.BEFORE_REVOKE, "revokeApprove", operator, null, null, request.reason());
        if (node.getNodeStatus() == WorkflowNodeStatus.COMPLETED) {
            var route = routes.stream().filter(item -> node.getNodeKey().equals(item.getSourceNodeKey())
                    && item.getRouteStatus() == WorkflowRouteStatus.EFFECTIVE).findFirst().orElseThrow();
            var next = nodes.stream().filter(item -> route.getTargetNodeKey().equals(item.getNodeKey())).findFirst().orElseThrow();
            for (var downstream : tasks) if (next.getId().equals(downstream.getNodeInstanceId())
                    && downstream.getTaskStatus() == WorkflowTaskStatus.TODO
                    && (next.getActivatedAt() == null || downstream.getCreatedAt() == null
                    || !downstream.getCreatedAt().isBefore(next.getActivatedAt()))) {
                downstream.setTaskStatus(WorkflowTaskStatus.CANCELED); downstream.setDecision("revokeApprove");
                downstream.setCompletedAt(now); updateTask(downstream, now);
                eventDao.insert(eventFactory.taskCompleted(instance, downstream, "revokeApprove", operator, request.reason(), now));
            }
            resetNodeForRetry(next); updateNode(next, now);
            resetRouteForRetry(route); updateRoute(route, now);
            node.setNodeStatus(WorkflowNodeStatus.ACTIVE); node.setCompletedAt(null);
        }
        task.setTaskStatus(WorkflowTaskStatus.ROLLED_BACK); task.setDecision("revokeApprove");
        task.setResultMessage(request.reason()); updateTask(task, now);
        int remainingVotes = (int) tasks.stream().filter(item -> node.getId().equals(item.getNodeInstanceId())
                && !task.getId().equals(item.getId()) && item.getTaskKind() == WorkflowTaskKind.APPROVAL
                && item.getTaskStatus() == WorkflowTaskStatus.DONE).count();
        node.setApprovedTaskCount(remainingVotes); node.setCompletedTaskCount(remainingVotes); updateNode(node, now);
        WorkflowTask retry = approvalRetry(task, now);
        for (var skipped : skippedVotes) {
            var restored = approvalRetry(skipped, now);
            eventDao.insert(eventFactory.taskCreated(instance, node, restored, operator, now));
        }
        var activeKeys = new java.util.LinkedHashSet<String>();
        nodes.stream().filter(item -> !node.getId().equals(item.getId()) && item.getNodeStatus() == WorkflowNodeStatus.ACTIVE)
                .map(WorkflowNodeInstance::getNodeKey).forEach(activeKeys::add);
        activeKeys.add(node.getNodeKey());
        instance.setCurrentNodeKeys(String.join(",", activeKeys)); instance.setLastActionCode("revokeApprove");
        instance.setLastActionReason(request.reason()); instance.setLastOperatorId(operator); instance.setLastOperatedAt(now); updateInstance(instance, now);
        WorkflowEvent event = eventFactory.taskCompleted(instance, task, "revokeApprove", operator, request.reason(), now);
        eventDao.insert(event); eventDao.insert(eventFactory.taskCreated(instance, node, retry, operator, now));
        dispatchTask(instance, node, task, WorkflowRuntimePluginEventType.AFTER_REVOKE, "revokeApprove", operator, null, null, request.reason());
        return new WorkflowTaskActionResult(task, retry, node, instance, event);
    }

    private WorkflowTask approvalRetry(WorkflowTask source, Instant now) {
        WorkflowTask retry = new WorkflowTask();
        org.springframework.beans.BeanUtils.copyProperties(source, retry);
        retry.setId(Ids.newId()); retry.setVersion(null);
        retry.setCreatedAt(null); retry.setCreatedBy(null); retry.setUpdatedAt(null); retry.setUpdatedBy(null); retry.setTaskStatus(WorkflowTaskStatus.TODO);
        retry.setParentTaskId(source.getId()); retry.setOriginTaskId(source.getOriginTaskId() == null ? source.getId() : source.getOriginTaskId());
        retry.setActualProcessorId(null); retry.setDecision(null); retry.setCompletedAt(null); retry.setResultMessage(null);
        EntityLifecycle.prepareInsert(retry, now); taskDao.insert(retry);
        return retry;
    }

    @Transactional
    public WorkflowTaskActionResult forceApprove(WorkflowTaskActionRequest request) {
        WorkflowTask task = requireTodoTask(request);
        if (task.getTaskKind() != WorkflowTaskKind.APPROVAL) {
            throw new PlatformException("workflow force approve only supports approval task: " + request.taskId());
        }
        WorkflowInstance instance = requireRunningInstance(task);
        WorkflowNodeInstance node = requireNode(task);
        Instant now = operatedAt(request);
        String operatorId = operatorId(request);
        actionPolicyService.requireManagementTaskAction("forceApprove", request.reason());
        dispatchTask(instance, node, task, WorkflowRuntimePluginEventType.BEFORE_APPROVE, "forceApprove",
                operatorId, null, null, request.reason());
        task.setTaskStatus(WorkflowTaskStatus.DONE);
        task.setActualProcessorId(operatorId);
        task.setDecision("forceApprove");
        task.setResultMessage(request.reason());
        task.setCompletedAt(now);
        updateTask(task, now);

        List<WorkflowTask> nodeTasks = nodeTasks(task);
        List<WorkflowTask> effectiveTasks = replaceTask(nodeTasks, task);
        if (approvalTaskPolicyService.isNodePassed(node.getApprovalMode(), node.getApprovalRatio(), effectiveTasks)) {
            node.setNodeStatus(WorkflowNodeStatus.COMPLETED);
            node.setApprovedTaskCount(countStatus(effectiveTasks, WorkflowTaskStatus.DONE));
            node.setCompletedTaskCount(countCompleted(effectiveTasks));
            node.setCompletedAt(now);
            updateNode(node, now);
            if (approvalTaskPolicyService.shouldSkipPendingSiblings(node.getApprovalMode(), node.getApprovalRatio(),
                    effectiveTasks)) {
                skipPendingSiblings(instance, task, effectiveTasks, operatorId, now);
            }
        }
        WorkflowEvent event = eventFactory.taskCompleted(instance, task, "forceApprove", operatorId,
                request.reason(), now);
        eventDao.insert(event);
        createDelegationCompletionNotice(instance, task, operatorId, now);
        if (node.getNodeStatus() == WorkflowNodeStatus.COMPLETED) {
            progressionService.advanceFromNode(instance.getId(), node.getNodeKey(), operatorId, now,
                    request.selectedRouteKey(), request.selectedReason(), request.manualRouteSelections());
        }
        dispatchTask(instance, node, task, WorkflowRuntimePluginEventType.AFTER_APPROVE, "forceApprove",
                operatorId, null, null, request.reason());
        return WorkflowTaskActionResult.of(task, node, instance, event);
    }

    @Transactional
    public WorkflowTaskActionResult reject(WorkflowTaskActionRequest request) {
        WorkflowTask task = requireTodoTask(request);
        if (task.getTaskKind() != WorkflowTaskKind.APPROVAL) {
            throw new PlatformException("workflow task is not an approval task: " + request.taskId());
        }
        WorkflowInstance instance = requireInstance(task);
        WorkflowNodeInstance node = requireNode(task);
        Instant now = operatedAt(request);
        String operatorId = operatorId(request);
        actionPolicyService.requireRuntimeAction(instance, "reject");
        actionPolicyService.requireNodeTaskAction(task, node, "reject", operatorId, request.reason());
        WorkflowRejectResubmitMode resubmitMode = request.rejectResubmitMode() == null
                ? WorkflowRejectResubmitMode.RESTART
                : request.rejectResubmitMode();
        if (resubmitMode == WorkflowRejectResubmitMode.RETURN_TO_ME) {
            actionPolicyService.requireRejectReturnToMe(node);
        }
        dispatchTask(instance, node, task, WorkflowRuntimePluginEventType.BEFORE_REJECT, "reject",
                operatorId, null, null, request.reason());
        task.setTaskStatus(WorkflowTaskStatus.REJECTED);
        task.setActualProcessorId(operatorId);
        task.setDecision("reject");
        task.setResultMessage(request.reason());
        task.setCompletedAt(now);
        updateTask(task, now);
        cancelOtherTodoTasks(instance, task.getId(), WorkflowTaskStatus.CANCELED, "reject", operatorId,
                request.reason(), now);

        node.setNodeStatus(WorkflowNodeStatus.REJECTED);
        node.setRejectedTaskCount(value(node.getRejectedTaskCount()) + 1);
        node.setCompletedAt(now);
        updateNode(node, now);

        instance.setInstanceStatus(WorkflowInstanceStatus.REJECTED);
        if (Boolean.TRUE.equals(instance.getApprovalEnabled())) {
            instance.setApprovalStatus(WorkflowApprovalStatus.REJECTED);
        }
        instance.setRejectResubmitMode(resubmitMode);
        instance.setRejectReturnNodeKey(resubmitMode == WorkflowRejectResubmitMode.RETURN_TO_ME
                ? node.getNodeKey() : null);
        instance.setRejectReturnOwnerId(resubmitMode == WorkflowRejectResubmitMode.RETURN_TO_ME
                ? operatorId : null);
        instance.setCurrentNodeKeys(null);
        instance.setLastActionCode("reject");
        instance.setLastActionReason(request.reason());
        instance.setLastOperatorId(operatorId);
        instance.setLastOperatedAt(now);
        updateInstance(instance, now);

        WorkflowTask resubmitTask = resubmitTask(instance, task, now);
        EntityLifecycle.prepareInsert(resubmitTask, now);
        taskDao.insert(resubmitTask);
        WorkflowEvent event = eventFactory.taskRejected(instance, task, operatorId, request.reason(), now);
        eventDao.insert(event);
        createDelegationCompletionNotice(instance, task, operatorId, now);
        eventDao.insert(eventFactory.taskCreated(instance, resubmitTask, operatorId, now));
        writeApprovalSummary(instance);
        dispatchTask(instance, node, task, WorkflowRuntimePluginEventType.AFTER_REJECT, "reject",
                operatorId, null, null, request.reason());
        return new WorkflowTaskActionResult(task, resubmitTask, node, instance, event);
    }

    @Transactional
    public WorkflowTaskActionResult rollback(WorkflowTaskActionRequest request) {
        WorkflowTask task = requireTodoTask(request);
        if (task.getTaskKind() != WorkflowTaskKind.APPROVAL) {
            throw new PlatformException("workflow task is not an approval task: " + request.taskId());
        }
        WorkflowInstance instance = requireRunningInstance(task);
        WorkflowNodeInstance currentNode = requireNode(task);
        if (currentNode.getNodeType() != WorkflowNodeType.APPROVAL) {
            throw new PlatformException("workflow rollback only supports approval node task: " + request.taskId());
        }
        Instant now = operatedAt(request);
        String operatorId = operatorId(request);
        actionPolicyService.requireRuntimeAction(instance, "rollback");
        actionPolicyService.requireNodeTaskAction(task, currentNode, "rollback", operatorId, request.reason());
        RollbackTarget target = previousApprovalNode(instance.getId(), currentNode);
        rejectRollbackWithOtherActiveNodes(instance.getId(), task, target);
        dispatchTask(instance, currentNode, task, WorkflowRuntimePluginEventType.BEFORE_ROLLBACK, "rollback",
                operatorId, null, target.node().getNodeKey(), request.reason());

        task.setTaskStatus(WorkflowTaskStatus.ROLLED_BACK);
        task.setActualProcessorId(operatorId);
        task.setDecision("rollback");
        task.setResultMessage(request.reason());
        task.setCompletedAt(now);
        updateTask(task, now);
        cancelOtherTodoTasks(instance, task.getId(), WorkflowTaskStatus.ROLLED_BACK, "rollback", operatorId,
                request.reason(), now);

        if (!target.crossBranchDomain()) {
            currentNode.setNodeStatus(WorkflowNodeStatus.ROLLED_BACK);
            currentNode.setRollbackTargetNodeKey(target.node().getNodeKey());
            currentNode.setCompletedAt(now);
            updateNode(currentNode, now);
        }

        WorkflowNodeInstance previousNode = target.node();
        resetApprovalNodeForRetry(previousNode, now);
        updateNode(previousNode, now);
        for (WorkflowNodeInstance intermediateNode : target.nodesToReset()) {
            resetNodeForRetry(intermediateNode);
            updateNode(intermediateNode, now);
        }

        for (WorkflowRouteInstance route : target.routesToReset()) {
            resetRouteForRetry(route);
            updateRoute(route, now);
        }

        instance.setInstanceStatus(WorkflowInstanceStatus.RUNNING);
        if (Boolean.TRUE.equals(instance.getApprovalEnabled())) {
            instance.setApprovalStatus(WorkflowApprovalStatus.PROCESSING);
        }
        instance.setCurrentNodeKeys(previousNode.getNodeKey());
        instance.setLastActionCode("rollback");
        instance.setLastActionReason(request.reason());
        instance.setLastOperatorId(operatorId);
        instance.setLastOperatedAt(now);
        updateInstance(instance, now);

        WorkflowTask createdTask = approvalTaskForNode(instance, previousNode, previousAssignee(previousNode), now);
        EntityLifecycle.prepareInsert(createdTask, now);
        taskDao.insert(createdTask);
        WorkflowEvent event = eventFactory.nodeRolledBack(instance, currentNode, operatorId, request.reason(), now);
        eventDao.insert(event);
        createDelegationCompletionNotice(instance, task, operatorId, now);
        eventDao.insert(eventFactory.taskCreated(instance, previousNode, createdTask, operatorId, now));
        writeApprovalSummary(instance);
        dispatchTask(instance, currentNode, task, WorkflowRuntimePluginEventType.AFTER_ROLLBACK, "rollback",
                operatorId, null, target.node().getNodeKey(), request.reason());
        return new WorkflowTaskActionResult(task, createdTask, previousNode, instance, event);
    }

    @Transactional
    public WorkflowTaskActionResult resubmit(WorkflowTaskActionRequest request) {
        WorkflowTask task = requireTodoTask(request);
        if (task.getTaskKind() != WorkflowTaskKind.RESUBMIT) {
            throw new PlatformException("workflow task is not a resubmit task: " + request.taskId());
        }
        WorkflowInstance instance = requireInstance(task);
        if (instance.getInstanceStatus() != WorkflowInstanceStatus.REJECTED) {
            throw new PlatformException("workflow instance is not rejected: " + instance.getId());
        }
        WorkflowRejectResubmitMode mode = instance.getRejectResubmitMode() == null
                ? WorkflowRejectResubmitMode.RESTART
                : instance.getRejectResubmitMode();
        if (mode == WorkflowRejectResubmitMode.RESTART) {
            throw new PlatformException("workflow restart resubmit requires new workflow submit entry: "
                    + instance.getId());
        }
        Instant now = operatedAt(request);
        String operatorId = operatorId(request);
        actionPolicyService.requireRuntimeAction(instance, "resubmit");
        actionPolicyService.requireTaskOperator(task, "resubmit", operatorId);
        task.setTaskStatus(WorkflowTaskStatus.DONE);
        task.setActualProcessorId(operatorId);
        task.setDecision("resubmit");
        task.setResultMessage(request.reason());
        task.setCompletedAt(now);
        updateTask(task, now);
        WorkflowNodeInstance node = rejectReturnNode(instance);
        String assigneeId = requireText(instance.getRejectReturnOwnerId(),
                "workflow reject return owner id must not be blank");
        resetApprovalNodeForRetry(node, now);
        updateNode(node, now);
        instance.setInstanceStatus(WorkflowInstanceStatus.RUNNING);
        if (Boolean.TRUE.equals(instance.getApprovalEnabled())) {
            instance.setApprovalStatus(WorkflowApprovalStatus.PROCESSING);
        }
        instance.setCurrentNodeKeys(node.getNodeKey());
        WorkflowTask createdTask = approvalTaskForNode(instance, node, assigneeId, now);
        EntityLifecycle.prepareInsert(createdTask, now);
        taskDao.insert(createdTask);
        eventDao.insert(eventFactory.taskCreated(instance, node, createdTask, operatorId, now));
        instance.setLastActionCode("resubmit_return_to_me");
        instance.setLastActionReason(request.reason());
        instance.setLastOperatorId(operatorId);
        instance.setLastOperatedAt(now);
        updateInstance(instance, now);
        WorkflowEvent event = eventFactory.taskResubmitted(instance, task, operatorId, request.reason(), now);
        eventDao.insert(event);
        writeApprovalSummary(instance);
        return new WorkflowTaskActionResult(task, createdTask, node, instance, event);
    }

    @Transactional
    public WorkflowTaskActionResult completeBusinessTask(WorkflowTaskActionRequest request) {
        WorkflowTask task = requireTodoTask(request);
        if (task.getTaskKind() != WorkflowTaskKind.BUSINESS) {
            throw new PlatformException("workflow task is not a business task: " + request.taskId());
        }
        WorkflowInstance instance = requireInstance(task);
        WorkflowNodeInstance node = requireNode(task);
        Instant now = operatedAt(request);
        String operatorId = operatorId(request);
        actionPolicyService.requireRuntimeAction(instance, "complete");
        actionPolicyService.requireTaskOperator(task, "complete", operatorId);
        if (instance.getInstanceStatus() != WorkflowInstanceStatus.RUNNING || node.getNodeStatus() != WorkflowNodeStatus.ACTIVE)
            throw new PlatformException("业务任务当前不能办理");
        dispatchTask(instance, node, task, WorkflowRuntimePluginEventType.BEFORE_COMPLETE, "complete",
                operatorId, null, null, request.reason());
        var specification = taskSpecifications.resolve(node);
        var evaluation = taskEvaluator.evaluate(instance, node, task, specification.definition());
        if (!evaluation.passed()) throw new PlatformException(evaluation.failureMessage() == null ? "业务任务尚未满足完成条件" : evaluation.failureMessage());
        for (var check : evaluation.checkResults()) {
            EntityLifecycle.prepareInsert(check, now); taskCheckResults.insert(check);
        }
        task.setCheckStatus(evaluation.checkStatus());
        task.setTaskStatus(WorkflowTaskStatus.DONE);
        task.setActualProcessorId(operatorId);
        task.setDecision("complete");
        task.setResultMessage(request.reason());
        task.setCompletedAt(now);
        updateTask(task, now);
        var remaining = nodeTasks(task).stream().filter(item -> !task.getId().equals(item.getId()))
                .anyMatch(item -> item.getTaskStatus() == WorkflowTaskStatus.TODO);
        node.setCompletedTaskCount(value(node.getCompletedTaskCount()) + 1);
        if (!remaining) { node.setNodeStatus(WorkflowNodeStatus.COMPLETED); node.setCompletedAt(now); }
        updateNode(node, now);
        WorkflowEvent event = eventFactory.taskCompleted(instance, task, "complete", operatorId,
                request.reason(), now);
        eventDao.insert(event);
        createDelegationCompletionNotice(instance, task, operatorId, now);
        if (!remaining) {
            instance = progressionService.advanceFromNode(instance.getId(), node.getNodeKey(), operatorId, now,
                    request.selectedRouteKey(), request.selectedReason(), request.manualRouteSelections()).instance();
        }
        dispatchTask(instance, node, task, WorkflowRuntimePluginEventType.AFTER_COMPLETE, "complete",
                operatorId, null, null, request.reason());
        return WorkflowTaskActionResult.of(task, node, instance, event);
    }

    @Transactional
    public WorkflowTaskActionResult notice(WorkflowTaskActionRequest request) {
        WorkflowTask task = requireTodoTask(request);
        if (task.getTaskKind() != WorkflowTaskKind.NOTICE) {
            throw new PlatformException("workflow task is not a notice task: " + request.taskId());
        }
        WorkflowInstance instance = requireInstance(task);
        Instant now = operatedAt(request);
        String operatorId = operatorId(request);
        actionPolicyService.requireRuntimeAction(instance, "notice");
        actionPolicyService.requireTaskOperator(task, "notice", operatorId);
        task.setTaskStatus(WorkflowTaskStatus.NOTICED);
        if (!isDelegationCompletionNotice(task)) {
            task.setActualProcessorId(operatorId);
            task.setCompletedAt(now);
        }
        task.setDecision("notice");
        task.setResultMessage(request.reason());
        updateTask(task, now);
        WorkflowEvent event = eventFactory.taskCompleted(instance, task, "notice", operatorId,
                request.reason(), now);
        eventDao.insert(event);
        return WorkflowTaskActionResult.of(task, event);
    }

    @Transactional
    public WorkflowTaskActionResult readNotice(WorkflowTaskActionRequest request) {
        WorkflowTask task = requireNoticeReadTask(request);
        WorkflowInstance instance = requireInstance(task);
        Instant now = operatedAt(request);
        String operatorId = operatorId(request);
        actionPolicyService.requireRuntimeAction(instance, "notice");
        requireNoticeOwner(task, operatorId);
        if (task.getTaskStatus() == WorkflowTaskStatus.NOTICED) {
            return WorkflowTaskActionResult.of(task, null);
        }
        task.setTaskStatus(WorkflowTaskStatus.NOTICED);
        if (!isDelegationCompletionNotice(task)) {
            task.setActualProcessorId(operatorId);
            task.setCompletedAt(now);
        }
        task.setDecision("notice");
        task.setResultMessage(request.reason());
        updateTask(task, now);
        WorkflowEvent event = eventFactory.taskCompleted(instance, task, "notice", operatorId,
                request.reason(), now);
        eventDao.insert(event);
        return WorkflowTaskActionResult.of(task, event);
    }

    @Transactional
    public WorkflowTaskActionResult transfer(WorkflowTaskActionRequest request) {
        WorkflowTask task = requireTodoTask(request);
        String targetAssigneeId = requireText(request.targetAssigneeId(), "workflow target assignee id must not be blank");
        WorkflowInstance instance = requireInstance(task);
        Instant now = operatedAt(request);
        String operatorId = operatorId(request);
        actionPolicyService.requireRuntimeAction(instance, "transfer");
        actionPolicyService.requireTaskOperator(task, "transfer", operatorId);
        WorkflowNodeInstance node = task.getNodeInstanceId() == null ? null : nodeInstanceDao.findById(task.getNodeInstanceId());
        dispatchTask(instance, node, task, WorkflowRuntimePluginEventType.BEFORE_TRANSFER, "transfer",
                operatorId, targetAssigneeId, null, request.reason());
        task.setTaskStatus(WorkflowTaskStatus.TRANSFERRED);
        task.setActualProcessorId(operatorId);
        task.setDecision("transfer");
        task.setTransferredBy(operatorId);
        task.setTransferredAt(now);
        task.setResultMessage(request.reason());
        task.setCompletedAt(now);
        updateTask(task, now);

        WorkflowTask createdTask = transferredTask(task, targetAssigneeId, operatorId, now);
        EntityLifecycle.prepareInsert(createdTask, now);
        taskDao.insert(createdTask);

        WorkflowEvent event = eventFactory.taskTransferred(instance, task, operatorId,
                "transfer to " + targetAssigneeId, request.reason(), now);
        eventDao.insert(event);
        dispatchTask(instance, node, createdTask, WorkflowRuntimePluginEventType.AFTER_TRANSFER, "transfer",
                operatorId, targetAssigneeId, null, request.reason());
        return WorkflowTaskActionResult.transferred(task, createdTask, event);
    }

    @Transactional
    public WorkflowTaskActionResult addSign(WorkflowTaskActionRequest request) {
        WorkflowTask task = requireTodoTask(request);
        if (task.getTaskKind() != WorkflowTaskKind.APPROVAL) {
            throw new PlatformException("workflow add sign only supports approval task: " + request.taskId());
        }
        WorkflowInstance instance = requireRunningInstance(task);
        WorkflowNodeInstance node = requireNode(task);
        if (node.getNodeType() != WorkflowNodeType.APPROVAL) {
            throw new PlatformException("workflow add sign only supports approval node task: " + request.taskId());
        }
        if (node.getNodeStatus() != WorkflowNodeStatus.ACTIVE) {
            throw new PlatformException("workflow add sign only supports active approval node: " + node.getNodeKey());
        }
        Instant now = operatedAt(request);
        String operatorId = operatorId(request);
        actionPolicyService.requireRuntimeAction(instance, "addSign");
        actionPolicyService.requireNodeTaskAction(task, node, "addSign", operatorId, request.reason());
        rejectMultiTodoApprovalTasks(task);
        EditableAddSignSegment existingSegment = loadEditableAddSignSegment(instance.getId(), node.getNodeKey());
        WorkflowAddSignEditMode editMode = existingSegment == null
                ? WorkflowAddSignEditMode.CREATE
                : WorkflowAddSignEditMode.REPLACE;
        List<WorkflowRouteInstance> originalRoutes = existingSegment == null
                ? outgoingCandidateRoutes(instance.getId(), node.getNodeKey())
                : existingSegment.restoreOriginalOutgoingRoutes(node.getNodeKey());
        if (originalRoutes.isEmpty()) {
            throw new PlatformException("workflow add sign requires candidate route after current node: "
                    + node.getNodeKey());
        }
        List<String> replacedRouteIds = existingSegment == null
                ? originalRoutes.stream().map(WorkflowRouteInstance::getId).toList()
                : existingSegment.routeIds();
        AddSignSegmentPlan plan = buildAddSignSegmentPlan(instance, node, originalRoutes, request.addSignSegment(),
                existingSegment == null ? Set.of() : existingSegment.nodeIds(), operatorId, now);
        validateAddSignManualTopology(instance.getId(), node.getNodeKey(), existingSegment,
                Set.copyOf(replacedRouteIds), plan);

        if (existingSegment == null) {
            for (WorkflowRouteInstance route : originalRoutes) {
                route.setRouteStatus(WorkflowRouteStatus.CANCELED);
                route.setRouteReason(WorkflowRouteReason.MANUAL_UNSELECTED);
                route.setSelectedBy(operatorId);
                route.setSelectedAt(now);
                route.setClosedReason("workflow route replaced by addSign");
                route.setInvalidatedByActionId("addSign");
                route.setInvalidatedAt(now);
                updateRoute(route, now);
            }
        } else {
            deleteEditableAddSignSegment(existingSegment);
        }
        for (WorkflowNodeInstance addedNode : plan.nodes()) {
            EntityLifecycle.prepareInsert(addedNode, now);
            nodeInstanceDao.insert(addedNode);
        }
        for (WorkflowRouteInstance addedRoute : plan.routes()) {
            EntityLifecycle.prepareInsert(addedRoute, now);
            routeInstanceDao.insert(addedRoute);
        }
        updateRenderJsonIfProvided(instance, request.semanticJson(), request.layoutJson(), now);

        WorkflowEvent event = eventFactory.addSign(instance, node, task, operatorId, request.reason(),
                addSignPayload(node.getNodeKey(), plan.addedNodeKeys(), replacedRouteIds, editMode,
                        request.semanticJson(), request.layoutJson()), now);
        eventDao.insert(event);
        return WorkflowTaskActionResult.addSign(task, node, instance, event, editMode,
                plan.addedNodeKeys(), replacedRouteIds);
    }

    private void updateRenderJsonIfProvided(WorkflowInstance instance, String semanticJson, String layoutJson,
                                            Instant now) {
        if ((semanticJson == null || semanticJson.isBlank()) && (layoutJson == null || layoutJson.isBlank())) {
            return;
        }
        if (semanticJson != null && !semanticJson.isBlank()) {
            instance.setSemanticJson(semanticJson);
        }
        if (layoutJson != null && !layoutJson.isBlank()) {
            instance.setLayoutJson(layoutJson);
        }
        updateInstance(instance, now);
    }

    @Transactional
    public WorkflowTaskActionResult invalidate(WorkflowTaskActionRequest request) {
        WorkflowTask task = requireTodoTask(request);
        WorkflowInstance instance = requireInstance(task);
        Instant now = operatedAt(request);
        String operatorId = operatorId(request);
        actionPolicyService.requireRuntimeAction(instance, "invalidate");
        actionPolicyService.requireTaskOperator(task, "invalidate", operatorId);
        task.setTaskStatus(WorkflowTaskStatus.INVALIDATED);
        task.setActualProcessorId(operatorId);
        task.setDecision("invalidate");
        task.setResultMessage(request.reason());
        task.setCompletedAt(now);
        updateTask(task, now);
        WorkflowEvent event = eventFactory.taskInvalidated(instance, task, operatorId, request.reason(), now);
        eventDao.insert(event);
        return WorkflowTaskActionResult.of(task, event);
    }

    @Transactional
    public WorkflowTaskActionResult cancel(WorkflowTaskActionRequest request) {
        WorkflowTask task = requireTodoTask(request);
        WorkflowInstance instance = requireInstance(task);
        Instant now = operatedAt(request);
        String operatorId = operatorId(request);
        actionPolicyService.requireRuntimeAction(instance, "cancel");
        actionPolicyService.requireTaskOperator(task, "cancel", operatorId);
        task.setTaskStatus(WorkflowTaskStatus.CANCELED);
        task.setActualProcessorId(operatorId);
        task.setDecision("cancel");
        task.setResultMessage(request.reason());
        task.setCompletedAt(now);
        updateTask(task, now);
        WorkflowEvent event = eventFactory.taskCanceled(instance, task, operatorId, request.reason(), now);
        eventDao.insert(event);
        return WorkflowTaskActionResult.of(task, event);
    }

    private WorkflowTask transferredTask(WorkflowTask source, String targetAssigneeId,
                                         String operatorId, Instant now) {
        WorkflowTask task = new WorkflowTask();
        task.setId(Ids.newId());
        task.setTenantId(source.getTenantId());
        task.setInstanceId(source.getInstanceId());
        task.setNodeInstanceId(source.getNodeInstanceId());
        task.setTaskKind(source.getTaskKind());
        task.setTaskStatus(WorkflowTaskStatus.TODO);
        task.setParentTaskId(source.getParentTaskId());
        task.setOriginTaskId(source.getOriginTaskId() == null ? source.getId() : source.getOriginTaskId());
        task.setAssignmentKind(WorkflowAssignmentKind.TRANSFERRED);
        task.setOwnerId(source.getOwnerId());
        task.setOriginalAssigneeId(source.getOriginalAssigneeId() == null
                ? source.getAssigneeId()
                : source.getOriginalAssigneeId());
        task.setAssigneeId(targetAssigneeId);
        task.setTransferredFromUserId(source.getAssigneeId());
        task.setTransferredBy(operatorId);
        task.setTransferredAt(now);
        task.setDelegatedFromUserId(source.getDelegatedFromUserId());
        task.setDelegatedToUserId(source.getDelegatedToUserId());
        task.setPrincipalCanProcess(source.getPrincipalCanProcess());
        task.setDelegationPolicyId(source.getDelegationPolicyId());
        task.setCheckStatus(source.getCheckStatus());
        task.setAssignmentPolicyText(source.getAssignmentPolicyText());
        task.setAssignmentSnapshotText(source.getAssignmentSnapshotText());
        task.setDueAt(source.getDueAt());
        return task;
    }

    private WorkflowTask resubmitTask(WorkflowInstance instance, WorkflowTask rejectedTask, Instant now) {
        WorkflowTask task = new WorkflowTask();
        task.setId(Ids.newId());
        task.setTenantId(instance.getTenantId());
        task.setInstanceId(instance.getId());
        task.setTaskKind(WorkflowTaskKind.RESUBMIT);
        task.setTaskStatus(WorkflowTaskStatus.TODO);
        task.setParentTaskId(rejectedTask.getId());
        task.setOriginTaskId(rejectedTask.getOriginTaskId() == null ? rejectedTask.getId() : rejectedTask.getOriginTaskId());
        task.setAssignmentKind(WorkflowAssignmentKind.NORMAL);
        task.setOwnerId(instance.getStartedBy());
        task.setOriginalAssigneeId(instance.getStartedBy());
        task.setAssigneeId(requireText(instance.getStartedBy(), "workflow started by must not be blank"));
        task.setCheckStatus(WorkflowTaskCheckStatus.NO_CHECK);
        return task;
    }

    private WorkflowTask approvalTaskForNode(WorkflowInstance instance, WorkflowNodeInstance node,
                                             String assigneeId, Instant now) {
        WorkflowTask task = new WorkflowTask();
        task.setId(Ids.newId());
        task.setTenantId(instance.getTenantId());
        task.setInstanceId(instance.getId());
        task.setNodeInstanceId(node.getId());
        task.setTaskKind(WorkflowTaskKind.APPROVAL);
        task.setTaskStatus(WorkflowTaskStatus.TODO);
        task.setAssignmentKind(WorkflowAssignmentKind.NORMAL);
        task.setOwnerId(assigneeId);
        task.setOriginalAssigneeId(assigneeId);
        task.setAssigneeId(requireText(assigneeId, "workflow approval assignee id must not be blank"));
        task.setCheckStatus(WorkflowTaskCheckStatus.NO_CHECK);
        applyDelegation(instance, task);
        return task;
    }

    private void applyDelegation(WorkflowInstance instance, WorkflowTask task) {
        if (delegationService == null) {
            return;
        }
        WorkflowDelegationMatch match = delegationService.match(task.getOriginalAssigneeId(),
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

    private void dispatchTask(WorkflowInstance instance,
                              WorkflowNodeInstance node,
                              WorkflowTask task,
                              WorkflowRuntimePluginEventType eventType,
                              String actionCode,
                              String operatorId,
                              String targetAssigneeId,
                              String rollbackTargetNodeKey,
                              String reason) {
        pluginDispatcher.dispatch(new WorkflowRuntimePluginContext(eventType, actionCode,
                instance == null ? null : instance.getModuleAlias(),
                instance == null ? null : instance.getRecordId(),
                instance == null ? null : instance.getId(),
                node == null ? null : node.getNodeKey(),
                task == null ? null : task.getId(),
                operatorId, targetAssigneeId, rollbackTargetNodeKey, null, reason, instance, node, task));
    }

    private WorkflowTask requireTodoTask(WorkflowTaskActionRequest request) {
        String taskId = requireText(request == null ? null : request.taskId(), "workflow task id must not be blank");
        WorkflowTask task = WorkflowMutationLock.task(taskDao, taskId);
        if (task == null) {
            throw new PlatformException("workflow task not found: " + taskId);
        }
        if (task.getTaskStatus() != WorkflowTaskStatus.TODO) {
            throw new PlatformException("workflow task is not todo: " + taskId);
        }
        return task;
    }

    private WorkflowTask requireNoticeReadTask(WorkflowTaskActionRequest request) {
        String taskId = requireText(request == null ? null : request.taskId(), "workflow task id must not be blank");
        WorkflowTask task = WorkflowMutationLock.task(taskDao, taskId);
        if (task == null) {
            throw new PlatformException("workflow task not found: " + taskId);
        }
        if (task.getTaskKind() != WorkflowTaskKind.NOTICE) {
            throw new PlatformException("workflow task is not a notice task: " + taskId);
        }
        if (task.getTaskStatus() != WorkflowTaskStatus.TODO && task.getTaskStatus() != WorkflowTaskStatus.NOTICED) {
            throw new PlatformException("workflow notice task is not readable: " + taskId);
        }
        return task;
    }

    private void requireNoticeOwner(WorkflowTask task, String operatorId) {
        String validOperatorId = requireText(operatorId, "workflow operator id must not be blank");
        if (!validOperatorId.equals(task.getAssigneeId())) {
            throw new PlatformException("workflow notice reader is not assignee: " + task.getId());
        }
    }

    private boolean isDelegationCompletionNotice(WorkflowTask task) {
        if (task == null || task.getTaskKind() != WorkflowTaskKind.NOTICE) {
            return false;
        }
        if (hasText(task.getDelegatedFromUserId())
                && hasText(task.getActualProcessorId())
                && !task.getActualProcessorId().equals(task.getDelegatedFromUserId())
                && (hasText(task.getDelegatedToUserId())
                || hasText(task.getDelegationPolicyId())
                || task.getAssignmentKind() == WorkflowAssignmentKind.DELEGATED
                || task.getAssignmentKind() == WorkflowAssignmentKind.TRANSFERRED)) {
            return true;
        }
        String snapshot = task.getAssignmentSnapshotText();
        return snapshot != null && snapshot.contains("DELEGATION_COMPLETED");
    }

    private WorkflowInstance requireInstance(WorkflowTask task) {
        WorkflowInstance instance = instanceDao.findById(task.getInstanceId());
        if (instance == null) {
            throw new PlatformException("workflow instance not found: " + task.getInstanceId());
        }
        return instance;
    }

    private WorkflowInstance requireRunningInstance(WorkflowTask task) {
        WorkflowInstance instance = requireInstance(task);
        if (instance.getInstanceStatus() != WorkflowInstanceStatus.RUNNING) {
            throw new PlatformException("workflow instance is not running: " + instance.getId());
        }
        return instance;
    }

    private WorkflowNodeInstance requireNode(WorkflowTask task) {
        WorkflowNodeInstance node = nodeInstanceDao.findById(task.getNodeInstanceId());
        if (node == null) {
            throw new PlatformException("workflow node instance not found: " + task.getNodeInstanceId());
        }
        return node;
    }

    private List<WorkflowTask> nodeTasks(WorkflowTask task) {
        return taskDao.query(Criteria.of()
                .eq("instanceId", task.getInstanceId())
                .eq("nodeInstanceId", task.getNodeInstanceId()), ALL);
    }

    private void rejectMultiTodoApprovalTasks(WorkflowTask task) {
        long todoApprovalCount = nodeTasks(task).stream()
                .filter(nodeTask -> nodeTask.getTaskKind() == WorkflowTaskKind.APPROVAL)
                .filter(nodeTask -> nodeTask.getTaskStatus() == WorkflowTaskStatus.TODO)
                .count();
        if (todoApprovalCount > 1) {
            throw new PlatformException("workflow add sign does not support multi todo approval tasks on one node: "
                    + task.getNodeInstanceId());
        }
    }

    private EditableAddSignSegment loadEditableAddSignSegment(String instanceId, String sourceNodeKey) {
        List<WorkflowNodeInstance> existingNodes = nodeInstanceDao.query(Criteria.of()
                .eq("instanceId", instanceId)
                .eq("addedByAddSign", true)
                .eq("addSignSourceNodeKey", sourceNodeKey), ALL);
        if (existingNodes.isEmpty()) {
            return null;
        }
        for (WorkflowNodeInstance existingNode : existingNodes) {
            if (existingNode.getNodeStatus() != WorkflowNodeStatus.WAITING) {
                throw new PlatformException("workflow add sign segment is already effective and cannot be edited: "
                        + sourceNodeKey);
            }
        }
        Set<String> nodeIds = existingNodes.stream()
                .map(WorkflowNodeInstance::getId)
                .collect(java.util.stream.Collectors.toSet());
        boolean hasTasks = taskDao.query(Criteria.of()
                        .eq("instanceId", instanceId), ALL).stream()
                .anyMatch(existingTask -> nodeIds.contains(existingTask.getNodeInstanceId()));
        if (hasTasks) {
            throw new PlatformException("workflow add sign segment is already effective and cannot be edited: "
                    + sourceNodeKey);
        }
        Set<String> nodeKeys = existingNodes.stream()
                .map(WorkflowNodeInstance::getNodeKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<WorkflowRouteInstance> existingRoutes = requireRouteDao().query(Criteria.of()
                .eq("instanceId", instanceId)
                .eq("addedByAddSign", true)
                .eq("addSignSourceNodeKey", sourceNodeKey), ALL);
        if (existingRoutes.isEmpty()) {
            throw new PlatformException("workflow add sign editable segment has no routes: " + sourceNodeKey);
        }
        return new EditableAddSignSegment(existingNodes, existingRoutes, nodeKeys);
    }

    private void deleteEditableAddSignSegment(EditableAddSignSegment segment) {
        for (WorkflowRouteInstance route : segment.routes()) {
            requireRouteDao().deleteById(route.getId());
        }
        for (WorkflowNodeInstance node : segment.nodes()) {
            nodeInstanceDao.deleteById(node.getId());
        }
    }

    private List<WorkflowRouteInstance> outgoingCandidateRoutes(String instanceId, String sourceNodeKey) {
        return requireRouteDao().query(Criteria.of()
                .eq("instanceId", instanceId)
                .eq("sourceNodeKey", sourceNodeKey)
                .eq("routeStatus", WorkflowRouteStatus.CANDIDATE), ALL);
    }

    private AddSignSegmentPlan buildAddSignSegmentPlan(WorkflowInstance instance,
                                                       WorkflowNodeInstance sourceNode,
                                                       List<WorkflowRouteInstance> originalRoutes,
                                                       WorkflowAddSignSegment segment,
                                                       Set<String> ignoredExistingNodeIds,
                                                       String operatorId,
                                                       Instant now) {
        if (segment == null) {
            throw new PlatformException("workflow add sign segment must not be null");
        }
        if (segment.nodeDefinitions().isEmpty()) {
            throw new PlatformException("workflow add sign segment must contain nodes");
        }
        if (segment.linkDefinitions().isEmpty()) {
            throw new PlatformException("workflow add sign segment must contain links");
        }
        if (originalRoutes.size() != 1) {
            throw new PlatformException("workflow add sign only supports single original next node in first version: "
                    + sourceNode.getNodeKey());
        }
        String originalNextNodeKey = requireText(originalRoutes.getFirst().getTargetNodeKey(),
                "workflow original next node key must not be blank");
        Set<String> existingNodeKeys = nodeInstanceDao.query(Criteria.of()
                        .eq("instanceId", instance.getId()), ALL).stream()
                .filter(node -> ignoredExistingNodeIds == null || !ignoredExistingNodeIds.contains(node.getId()))
                .map(WorkflowNodeInstance::getNodeKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, WorkflowNodeDefinition> definitionsByKey = new LinkedHashMap<>();
        for (WorkflowNodeDefinition definition : segment.nodeDefinitions()) {
            String nodeKey = requireText(definition.getNodeKey(), "workflow add sign node key must not be blank");
            if (definitionsByKey.containsKey(nodeKey)) {
                throw new PlatformException("workflow add sign node key duplicated: " + nodeKey);
            }
            if (existingNodeKeys.contains(nodeKey)) {
                throw new PlatformException("workflow add sign node key conflicts with instance node: " + nodeKey);
            }
            validateAddSignNodeDefinition(definition);
            definitionsByKey.put(nodeKey, definition);
        }
        Set<String> routeKeys = new LinkedHashSet<>();
        for (WorkflowLinkDefinition definition : segment.linkDefinitions()) {
            String routeKey = requireText(definition.getRouteKey(), "workflow add sign route key must not be blank");
            if (!routeKeys.add(routeKey)) {
                throw new PlatformException("workflow add sign route key duplicated: " + routeKey);
            }
        }
        validateAddSignSegmentGraph(sourceNode.getNodeKey(), originalNextNodeKey, definitionsByKey.keySet(),
                segment.linkDefinitions());
        List<WorkflowNodeInstance> nodes = segment.nodeDefinitions().stream()
                .map(definition -> addSignNode(instance, sourceNode, definition, operatorId, now))
                .toList();
        List<WorkflowRouteInstance> routes = segment.linkDefinitions().stream()
                .map(definition -> addSignRoute(instance, sourceNode, definition, operatorId, now))
                .toList();
        return new AddSignSegmentPlan(nodes, routes,
                nodes.stream().map(WorkflowNodeInstance::getNodeKey).toList());
    }

    private void validateAddSignManualTopology(String instanceId, String sourceNodeKey,
                                               EditableAddSignSegment existingSegment,
                                               Set<String> replacedRouteIds, AddSignSegmentPlan plan) {
        Set<String> replacedNodeIds = existingSegment == null ? Set.of() : existingSegment.nodeIds();
        var nodes = new ArrayList<>(nodeInstanceDao.query(Criteria.of().eq("instanceId", instanceId), ALL).stream()
                .filter(node -> !replacedNodeIds.contains(node.getId())).toList());
        nodes.addAll(plan.nodes());
        var routes = new ArrayList<>(requireRouteDao().query(Criteria.of().eq("instanceId", instanceId), ALL).stream()
                .filter(route -> !replacedRouteIds.contains(route.getId()))
                .filter(route -> route.getRouteStatus() == WorkflowRouteStatus.CANDIDATE
                        || route.getRouteStatus() == WorkflowRouteStatus.EFFECTIVE
                        || route.getRouteStatus() == WorkflowRouteStatus.CLOSED)
                .filter(route -> route.getInvalidatedByActionId() == null && route.getInvalidatedAt() == null)
                .toList());
        routes.addAll(plan.routes());
        var graph = WorkflowManualBranchFrontier.frozenGraph(nodes, routes);
        var outgoing = new LinkedHashMap<String, List<String>>();
        graph.nodes().keySet().forEach(key -> outgoing.put(key, graph.outgoing(key).stream()
                .map(WorkflowLinkDefinition::getTargetNodeKey).toList()));
        // Only downstream decisions can change; completed or pruned historical decisions have no new action boundary.
        Set<String> affected = reachable(sourceNodeKey, outgoing);
        for (var node : nodes) {
            if (!affected.contains(node.getNodeKey()) || node.getNodeType() != WorkflowNodeType.BRANCH
                    || node.getRouteMode() != WorkflowRouteMode.MANUAL
                    || node.getNodeStatus() != WorkflowNodeStatus.WAITING && node.getNodeStatus() != WorkflowNodeStatus.ACTIVE)
                continue;
            try {
                WorkflowDesignCompiler.validateManualSelectionTopology(graph, graph.requireNode(node.getNodeKey()));
            } catch (PlatformException invalid) {
                throw new PlatformException("加签会破坏手工分支的选路责任，请调整加签位置: " + invalid.getMessage(), invalid);
            }
        }
    }

    private void validateAddSignSegmentGraph(String sourceNodeKey,
                                             String originalNextNodeKey,
                                             Set<String> addedNodeKeys,
                                             List<WorkflowLinkDefinition> linkDefinitions) {
        Set<String> allowed = new LinkedHashSet<>();
        allowed.add(sourceNodeKey);
        allowed.addAll(addedNodeKeys);
        allowed.add(originalNextNodeKey);
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        Map<String, List<String>> incoming = new LinkedHashMap<>();
        Set<String> edges = new LinkedHashSet<>();
        for (WorkflowLinkDefinition link : linkDefinitions) {
            String source = requireText(link.getSourceNodeKey(), "workflow add sign link source must not be blank");
            String target = requireText(link.getTargetNodeKey(), "workflow add sign link target must not be blank");
            if (!allowed.contains(source) || !allowed.contains(target)) {
                throw new PlatformException("workflow add sign link must stay inside local segment: "
                        + source + " -> " + target);
            }
            if (sourceNodeKey.equals(target)) {
                throw new PlatformException("workflow add sign link cannot target source node: " + target);
            }
            if (originalNextNodeKey.equals(source)) {
                throw new PlatformException("workflow add sign link cannot leave original next node: " + source);
            }
            if (sourceNodeKey.equals(source) && originalNextNodeKey.equals(target)) {
                throw new PlatformException("workflow add sign segment cannot be empty direct route");
            }
            if (!edges.add(source + "->" + target)) {
                throw new PlatformException("workflow add sign link duplicated: " + source + " -> " + target);
            }
            outgoing.computeIfAbsent(source, ignored -> new ArrayList<>()).add(target);
            incoming.computeIfAbsent(target, ignored -> new ArrayList<>()).add(source);
        }
        if (outgoing.getOrDefault(sourceNodeKey, List.of()).isEmpty()) {
            throw new PlatformException("workflow add sign segment must have at least one entry from source node");
        }
        if (incoming.getOrDefault(originalNextNodeKey, List.of()).size() != 1) {
            throw new PlatformException("workflow add sign segment must have one exit to original next node");
        }
        Set<String> reachableFromSource = reachable(sourceNodeKey, outgoing);
        Map<String, List<String>> reverse = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : outgoing.entrySet()) {
            for (String target : entry.getValue()) {
                reverse.computeIfAbsent(target, ignored -> new ArrayList<>()).add(entry.getKey());
            }
        }
        Set<String> canReachOriginalNext = reachable(originalNextNodeKey, reverse);
        for (String addedNodeKey : addedNodeKeys) {
            if (!reachableFromSource.contains(addedNodeKey)) {
                throw new PlatformException("workflow add sign segment contains unreachable added node: "
                        + addedNodeKey);
            }
            if (!canReachOriginalNext.contains(addedNodeKey)) {
                throw new PlatformException("workflow add sign segment added node cannot reach original next node: "
                        + addedNodeKey);
            }
        }
        if (!reachableFromSource.contains(originalNextNodeKey)) {
            throw new PlatformException("workflow add sign segment must return to original next node: "
                    + originalNextNodeKey);
        }
        rejectAddSignSegmentCycles(sourceNodeKey, originalNextNodeKey, addedNodeKeys, outgoing);
    }

    private void validateAddSignNodeDefinition(WorkflowNodeDefinition definition) {
        WorkflowNodeType nodeType = definition.getNodeType();
        if (nodeType != WorkflowNodeType.APPROVAL
                && nodeType != WorkflowNodeType.BRANCH
                && nodeType != WorkflowNodeType.CONVERGE) {
            throw new PlatformException("workflow add sign segment only supports approval, branch and converge nodes: "
                    + definition.getNodeKey());
        }
        if (nodeType != WorkflowNodeType.APPROVAL) {
            return;
        }
        String policy = definition.getParticipantPolicyText();
        if (policy == null || policy.isBlank()) {
            throw new PlatformException("workflow add sign approval node participant policy is required: "
                    + definition.getNodeKey());
        }
        WorkflowParticipantPolicyCodec.validate(policy, definition.getNodeKey());
    }

    private Set<String> reachable(String start, Map<String, List<String>> outgoing) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (!visited.add(current)) {
                continue;
            }
            for (String next : outgoing.getOrDefault(current, List.of())) {
                stack.push(next);
            }
        }
        return visited;
    }

    private void rejectAddSignSegmentCycles(String sourceNodeKey,
                                            String originalNextNodeKey,
                                            Set<String> addedNodeKeys,
                                            Map<String, List<String>> outgoing) {
        Set<String> visited = new LinkedHashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        detectAddSignCycle(sourceNodeKey, originalNextNodeKey, addedNodeKeys, outgoing, visited, visiting);
    }

    private void detectAddSignCycle(String nodeKey,
                                    String originalNextNodeKey,
                                    Set<String> addedNodeKeys,
                                    Map<String, List<String>> outgoing,
                                    Set<String> visited,
                                    Set<String> visiting) {
        if (!addedNodeKeys.contains(nodeKey) && !originalNextNodeKey.equals(nodeKey)) {
            if (!visiting.add(nodeKey)) {
                throw new PlatformException("workflow add sign segment contains invalid cycle: " + nodeKey);
            }
        } else if (!visiting.add(nodeKey)) {
            throw new PlatformException("workflow add sign segment contains invalid cycle: " + nodeKey);
        }
        for (String next : outgoing.getOrDefault(nodeKey, List.of())) {
            if (visited.contains(next)) {
                continue;
            }
            detectAddSignCycle(next, originalNextNodeKey, addedNodeKeys, outgoing, visited, visiting);
        }
        visiting.remove(nodeKey);
        visited.add(nodeKey);
    }

    private WorkflowNodeInstance addSignNode(WorkflowInstance instance,
                                             WorkflowNodeInstance sourceNode,
                                             WorkflowNodeDefinition definition,
                                             String operatorId,
                                             Instant now) {
        WorkflowNodeInstance node = new WorkflowNodeInstance();
        node.setId(Ids.newId());
        node.setTenantId(instance.getTenantId());
        node.setInstanceId(instance.getId());
        node.setNodeKey(requireText(definition.getNodeKey(), "workflow add sign node key must not be blank"));
        node.setNodeTitle(firstText(definition.getTitle(), definition.getNodeKey()));
        node.setNodeRunId(node.getNodeKey() + ":addSign:1");
        node.setNodeType(definition.getNodeType());
        node.setNodeStatus(WorkflowNodeStatus.WAITING);
        node.setApprovalMode(definition.getApprovalMode());
        node.setApprovalRatio(definition.getApprovalRatio());
        node.setMilestoneType(definition.getMilestoneType());
        node.setConvergeMode(definition.getConvergeMode());
        node.setConvergeRatio(definition.getConvergeRatio());
        node.setRouteMode(definition.getRouteMode());
        node.setSelectorNodeKey(definition.getSelectorNodeKey());
        node.setRequireManualSelectionReason(definition.getRequireManualSelectionReason());
        node.setTaskDefinitionId(definition.getTaskDefinitionId());
        node.setParticipantPolicyText(definition.getParticipantPolicyText());
        node.setAllowReject(definition.getAllowReject());
        node.setRequireRejectReason(definition.getRequireRejectReason());
        node.setAllowRejectReturnToMe(definition.getAllowRejectReturnToMe());
        node.setAllowRollback(definition.getAllowRollback());
        node.setRequireRollbackReason(definition.getRequireRollbackReason());
        node.setAllowTerminate(definition.getAllowTerminate());
        node.setRequireTerminateReason(definition.getRequireTerminateReason());
        node.setAllowAddSign(definition.getAllowAddSign());
        node.setWarningDurationMinutes(definition.getWarningDurationMinutes());
        node.setOvertimeDurationMinutes(definition.getOvertimeDurationMinutes());
        node.setNodeSnapshotText(definition.getNodeConfigText());
        node.setAddedByAddSign(true);
        node.setAddSignSourceNodeKey(sourceNode.getNodeKey());
        node.setAddSignOperatorId(operatorId);
        node.setAddSignAt(now);
        return node;
    }

    private WorkflowRouteInstance addSignRoute(WorkflowInstance instance,
                                               WorkflowNodeInstance sourceNode,
                                               WorkflowLinkDefinition definition,
                                               String operatorId,
                                               Instant now) {
        WorkflowRouteInstance route = new WorkflowRouteInstance();
        route.setId(Ids.newId());
        route.setTenantId(instance.getTenantId());
        route.setInstanceId(instance.getId());
        route.setRouteKey(requireText(definition.getRouteKey(), "workflow add sign route key must not be blank"));
        route.setRouteRunId(route.getRouteKey() + ":addSign:1");
        route.setSourceNodeKey(requireText(definition.getSourceNodeKey(),
                "workflow add sign route source node key must not be blank"));
        route.setTargetNodeKey(requireText(definition.getTargetNodeKey(),
                "workflow add sign route target node key must not be blank"));
        route.setRouteStatus(WorkflowRouteStatus.CANDIDATE);
        route.setDefaultRoute(definition.getDefaultRoute());
        route.setAddedByAddSign(true);
        route.setAddSignSourceNodeKey(sourceNode.getNodeKey());
        route.setAddSignOperatorId(operatorId);
        route.setAddSignAt(now);
        return route;
    }

    private String addSignPayload(String sourceNodeKey,
                                  List<String> addedNodeKeys,
                                  List<String> replacedRouteIds,
                                  WorkflowAddSignEditMode editMode,
                                  String semanticJson,
                                  String layoutJson) {
        return "{\"sourceNodeKey\":\"" + escapeJson(sourceNodeKey)
                + "\",\"addedNodeKeys\":" + jsonArray(addedNodeKeys)
                + ",\"replacedRouteIds\":" + jsonArray(replacedRouteIds)
                + ",\"editMode\":\"" + escapeJson(editMode.getCode()) + "\""
                + optionalJsonString("semanticJson", semanticJson)
                + optionalJsonString("layoutJson", layoutJson)
                + "}";
    }

    private String jsonArray(List<String> values) {
        return values == null || values.isEmpty()
                ? "[]"
                : values.stream()
                        .map(value -> "\"" + escapeJson(value) + "\"")
                        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private String optionalJsonString(String fieldName, String value) {
        return value == null || value.isBlank() ? "" : ",\"" + fieldName + "\":\"" + escapeJson(value) + "\"";
    }

    private String escapeJson(String value) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        return builder.toString();
    }

    private List<WorkflowTask> instanceTodoTasks(String instanceId) {
        return taskDao.query(Criteria.of()
                .eq("instanceId", instanceId)
                .eq("taskStatus", WorkflowTaskStatus.TODO), ALL);
    }

    private void cancelOtherTodoTasks(WorkflowInstance instance, String currentTaskId, WorkflowTaskStatus taskStatus,
                                      String decision, String operatorId, String message, Instant now) {
        for (WorkflowTask other : instanceTodoTasks(instance.getId())) {
            if (currentTaskId.equals(other.getId())) {
                continue;
            }
            other.setTaskStatus(taskStatus);
            other.setActualProcessorId(operatorId);
            other.setDecision(decision);
            other.setResultMessage(message);
            other.setCompletedAt(now);
            updateTask(other, now);
            eventDao.insert(taskStatus == WorkflowTaskStatus.CANCELED
                    ? eventFactory.taskCanceled(instance, other, operatorId, message, now)
                    : eventFactory.nodeRolledBack(instance, requireNode(other), operatorId, message, now));
        }
    }

    private void rejectRollbackWithOtherActiveNodes(String instanceId, WorkflowTask currentTask,
                                                    RollbackTarget target) {
        for (WorkflowTask other : instanceTodoTasks(instanceId)) {
            if (currentTask.getId().equals(other.getId())) {
                continue;
            }
            if (!sameText(currentTask.getNodeInstanceId(), other.getNodeInstanceId())
                    && !target.containsResetNodeId(other.getNodeInstanceId())) {
                throw new PlatformException("workflow rollback only supports single active node in first version: "
                        + instanceId);
            }
        }
    }

    private RollbackTarget previousApprovalNode(String instanceId, WorkflowNodeInstance currentNode) {
        WorkflowNodeInstance cursor = currentNode;
        List<WorkflowRouteInstance> pathRoutes = new ArrayList<>();
        List<WorkflowNodeInstance> intermediateNodes = new ArrayList<>();
        WorkflowRouteInstance branchScopeRoute = null;
        boolean crossedBranchBoundary = false;
        for (int depth = 0; depth < 64; depth++) {
            List<WorkflowRouteInstance> incoming = requireRouteDao().query(Criteria.of()
                    .eq("instanceId", instanceId)
                    .eq("targetNodeKey", cursor.getNodeKey())
                    .eq("routeStatus", WorkflowRouteStatus.EFFECTIVE), ALL);
            if (incoming.isEmpty()) {
                throw new PlatformException("workflow rollback previous approval node not found: "
                        + currentNode.getNodeKey());
            }
            if (incoming.size() != 1) {
                throw new PlatformException("workflow rollback only supports linear effective route: "
                        + currentNode.getNodeKey());
            }
            WorkflowRouteInstance route = incoming.get(0);
            rejectNestedRollbackRoute(route);
            if (hasBranchScope(route)) {
                if (branchScopeRoute == null) {
                    branchScopeRoute = route;
                } else if (!sameRollbackBranchScope(branchScopeRoute, route)) {
                    throw new PlatformException("workflow rollback only supports one branch domain: "
                            + currentNode.getNodeKey());
                }
            } else if (branchScopeRoute == null) {
                rejectComplexRollbackRoute(route);
            }
            pathRoutes.add(route);
            WorkflowNodeInstance source = singleNode(instanceId, route.getSourceNodeKey());
            if (source.getNodeType() == WorkflowNodeType.APPROVAL) {
                if (branchScopeRoute != null && !crossedBranchBoundary) {
                    throw new PlatformException("workflow rollback only supports branch domain to pre-branch node: "
                            + source.getNodeKey());
                }
                if (source.getNodeStatus() != WorkflowNodeStatus.COMPLETED) {
                    throw new PlatformException("workflow rollback previous approval node is not completed: "
                            + source.getNodeKey());
                }
                return branchScopeRoute == null
                        ? RollbackTarget.linear(source, pathRoutes, intermediateNodes)
                        : RollbackTarget.crossBranch(source, pathRoutes, intermediateNodes,
                                branchDomainForRollback(instanceId, branchScopeRoute, source.getNodeKey()));
            }
            if (source.getNodeType() == WorkflowNodeType.BRANCH || source.getNodeType() == WorkflowNodeType.CONVERGE) {
                if (branchScopeRoute != null
                        && source.getNodeType() == WorkflowNodeType.BRANCH
                        && sameText(source.getNodeKey(), branchScopeRoute.getBranchNodeKey())) {
                    crossedBranchBoundary = true;
                } else {
                    throw new PlatformException("workflow rollback only supports branch domain to pre-branch node: "
                            + source.getNodeKey());
                }
            }
            intermediateNodes.add(source);
            cursor = source;
        }
        throw new PlatformException("workflow rollback path exceeded max depth: " + currentNode.getNodeKey());
    }

    private WorkflowNodeInstance singleNode(String instanceId, String nodeKey) {
        List<WorkflowNodeInstance> sources = nodeInstanceDao.query(Criteria.of()
                .eq("instanceId", instanceId)
                .eq("nodeKey", nodeKey), ALL);
        if (sources.size() != 1) {
            throw new PlatformException("workflow rollback source node is not unique: " + nodeKey);
        }
        return sources.get(0);
    }

    private void rejectComplexRollbackRoute(WorkflowRouteInstance route) {
        if (hasText(route.getBranchNodeKey()) || hasText(route.getBranchRunId())
                || hasText(route.getConvergeNodeKey()) || hasText(route.getConvergeRunId())
                || hasText(route.getParentRouteId())) {
            throw new PlatformException("workflow rollback only supports linear route in first version: "
                    + route.getRouteKey());
        }
    }

    private void rejectNestedRollbackRoute(WorkflowRouteInstance route) {
        if (hasText(route.getParentRouteId()) || (route.getRouteDepth() != null && route.getRouteDepth() > 1)) {
            throw new PlatformException("workflow rollback does not support nested branch route: "
                    + route.getRouteKey());
        }
    }

    private boolean hasBranchScope(WorkflowRouteInstance route) {
        return hasText(route.getBranchNodeKey()) || hasText(route.getBranchRunId())
                || hasText(route.getConvergeNodeKey()) || hasText(route.getConvergeRunId());
    }

    private boolean sameRollbackBranchScope(WorkflowRouteInstance left, WorkflowRouteInstance right) {
        return sameText(left.getBranchNodeKey(), right.getBranchNodeKey())
                && sameText(left.getBranchRunId(), right.getBranchRunId())
                && sameText(left.getConvergeNodeKey(), right.getConvergeNodeKey())
                && sameText(left.getConvergeRunId(), right.getConvergeRunId())
                && sameText(left.getParentRouteId(), right.getParentRouteId());
    }

    private RollbackBranchDomain branchDomainForRollback(String instanceId, WorkflowRouteInstance scopeRoute,
                                                         String targetNodeKey) {
        List<WorkflowRouteInstance> routes = requireRouteDao().query(Criteria.of()
                .eq("instanceId", instanceId), ALL);
        List<WorkflowRouteInstance> domainRoutes = routes.stream()
                .filter(route -> sameRollbackBranchScope(scopeRoute, route))
                .toList();
        domainRoutes.forEach(this::rejectNestedRollbackRoute);
        if (domainRoutes.isEmpty()) {
            throw new PlatformException("workflow rollback branch domain route not found: "
                    + scopeRoute.getBranchNodeKey());
        }
        Set<String> nodeKeys = branchDomainNodeKeys(scopeRoute, domainRoutes);
        nodeKeys.remove(targetNodeKey);
        List<WorkflowNodeInstance> nodes = nodeInstanceDao.query(Criteria.of()
                        .eq("instanceId", instanceId), ALL)
                .stream()
                .filter(node -> nodeKeys.contains(node.getNodeKey()))
                .toList();
        if (nodes.size() != nodeKeys.size()) {
            throw new PlatformException("workflow rollback branch domain node not found: "
                    + scopeRoute.getBranchNodeKey());
        }
        return new RollbackBranchDomain(nodes, domainRoutes);
    }

    private Set<String> branchDomainNodeKeys(WorkflowRouteInstance scopeRoute,
                                             List<WorkflowRouteInstance> domainRoutes) {
        Set<String> nodeKeys = new LinkedHashSet<>();
        if (hasText(scopeRoute.getBranchNodeKey())) {
            nodeKeys.add(scopeRoute.getBranchNodeKey());
        }
        if (hasText(scopeRoute.getConvergeNodeKey())) {
            nodeKeys.add(scopeRoute.getConvergeNodeKey());
        }
        for (WorkflowRouteInstance route : domainRoutes) {
            nodeKeys.add(route.getSourceNodeKey());
            if (!sameText(route.getSourceNodeKey(), scopeRoute.getConvergeNodeKey())) {
                nodeKeys.add(route.getTargetNodeKey());
            }
        }
        return nodeKeys;
    }

    private void resetApprovalNodeForRetry(WorkflowNodeInstance node, Instant now) {
        node.setNodeStatus(WorkflowNodeStatus.ACTIVE);
        node.setCompletedAt(null);
        node.setRollbackTargetNodeKey(null);
        node.setCompletedTaskCount(0);
        node.setApprovedTaskCount(0);
        node.setRejectedTaskCount(0);
        node.setActivatedAt(now);
    }

    private void resetNodeForRetry(WorkflowNodeInstance node) {
        node.setNodeStatus(WorkflowNodeStatus.WAITING);
        node.setRouteId(null);
        node.setEnterRouteId(null);
        node.setBranchRunId(null);
        node.setConvergeRunId(null);
        node.setRequiredRouteCount(null);
        node.setArrivedRouteCount(0);
        node.setCompletedRouteCount(0);
        node.setRequiredTaskCount(null);
        node.setCompletedTaskCount(0);
        node.setApprovedTaskCount(0);
        node.setRejectedTaskCount(0);
        node.setRollbackTargetNodeKey(null);
        node.setActivatedAt(null);
        node.setCompletedAt(null);
    }

    private void resetRouteForRetry(WorkflowRouteInstance route) {
        route.setRouteStatus(WorkflowRouteStatus.CANDIDATE);
        route.setRouteReason(null);
        route.setConditionMatched(false);
        route.setSelectedBy(null);
        route.setSelectedAt(null);
        route.setSelectedReason(null);
        route.setArrivedAt(null);
        route.setClosedByRouteId(null);
        route.setClosedReason(null);
        route.setInvalidatedByActionId(null);
        route.setInvalidatedAt(null);
    }

    private String previousAssignee(WorkflowNodeInstance previousNode) {
        return taskDao.query(Criteria.of()
                        .eq("instanceId", previousNode.getInstanceId())
                        .eq("nodeInstanceId", previousNode.getId()), ALL)
                .stream()
                .filter(task -> task.getTaskKind() == WorkflowTaskKind.APPROVAL)
                .filter(task -> task.getTaskStatus() == WorkflowTaskStatus.DONE)
                .sorted(Comparator.comparing(WorkflowTask::getCompletedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(task -> firstText(task.getActualProcessorId(), task.getAssigneeId(), task.getOwnerId()))
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElseThrow(() -> new PlatformException("workflow rollback previous approval assignee not found: "
                        + previousNode.getNodeKey()));
    }

    private WorkflowNodeInstance rejectReturnNode(WorkflowInstance instance) {
        String nodeKey = requireText(instance.getRejectReturnNodeKey(),
                "workflow reject return node key must not be blank");
        List<WorkflowNodeInstance> nodes = nodeInstanceDao.query(Criteria.of()
                .eq("instanceId", instance.getId())
                .eq("nodeKey", nodeKey), ALL);
        if (nodes.size() != 1) {
            throw new PlatformException("workflow reject return node is not unique: " + nodeKey);
        }
        return nodes.get(0);
    }

    private List<WorkflowTask> replaceTask(List<WorkflowTask> tasks, WorkflowTask changed) {
        List<WorkflowTask> result = new ArrayList<>();
        boolean replaced = false;
        for (WorkflowTask task : tasks == null ? List.<WorkflowTask>of() : tasks) {
            if (changed.getId().equals(task.getId())) {
                result.add(changed);
                replaced = true;
            } else {
                result.add(task);
            }
        }
        if (!replaced) {
            result.add(changed);
        }
        return result;
    }

    private void skipPendingSiblings(WorkflowInstance instance, WorkflowTask completedTask,
                                     List<WorkflowTask> tasks, String operatorId, Instant now) {
        for (WorkflowTask task : tasks) {
            if (task == completedTask || completedTask.getId().equals(task.getId())
                    || task.getTaskStatus() != WorkflowTaskStatus.TODO) {
                continue;
            }
            task.setTaskStatus(WorkflowTaskStatus.SKIPPED);
            task.setDecision("skip");
            task.setResultMessage("approval node passed");
            task.setCompletedAt(now);
            updateTask(task, now);
            eventDao.insert(eventFactory.taskSkipped(instance, task, operatorId, "approval node passed", now));
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

    private void createDelegationCompletionNotice(WorkflowInstance instance, WorkflowTask task,
                                                  String operatorId, Instant now) {
        if (delegationCompletionNoticeService == null) {
            return;
        }
        delegationCompletionNoticeService.createIfNeeded(instance, task, operatorId, now);
    }

    private void updateNode(WorkflowNodeInstance node, Instant now) {
        Integer expectedVersion = node.getVersion();
        EntityLifecycle.prepareUpdate(node, now, EntityLifecycle.nextVersion(expectedVersion));
        int updated = nodeInstanceDao.updateByIdAndVersion(node, expectedVersion);
        if (updated <= 0) {
            throw new OptimisticLockException("workflow node version conflict: " + node.getId());
        }
    }

    private void updateInstance(WorkflowInstance instance, Instant now) {
        Integer expectedVersion = instance.getVersion();
        EntityLifecycle.prepareUpdate(instance, now, EntityLifecycle.nextVersion(expectedVersion));
        int updated = instanceDao.updateByIdAndVersion(instance, expectedVersion);
        if (updated <= 0) {
            throw new OptimisticLockException("workflow instance version conflict: " + instance.getId());
        }
    }

    private void updateRoute(WorkflowRouteInstance route, Instant now) {
        Integer expectedVersion = route.getVersion();
        EntityLifecycle.prepareUpdate(route, now, EntityLifecycle.nextVersion(expectedVersion));
        int updated = routeInstanceDao.updateByIdAndVersion(route, expectedVersion);
        if (updated <= 0) {
            throw new OptimisticLockException("workflow route version conflict: " + route.getId());
        }
    }

    private void writeApprovalSummary(WorkflowInstance instance) {
        if (!Boolean.TRUE.equals(instance.getApprovalEnabled())) {
            return;
        }
        String actionCode = "resubmit_return_to_me".equals(instance.getLastActionCode()) ? "resubmit" : instance.getLastActionCode();
        WorkflowApprovalMutationScope.run(instance.getModuleAlias(), instance.getRecordId(), actionCode,
                () -> approvalSummaryWriter.ifPresent(writer -> writer.writeSubmitted(new WorkflowApprovalSummary(
                instance.getTenantId(),
                instance.getModuleAlias(),
                instance.getRecordId(),
                instance.getId(),
                instance.getApprovalStatus(),
                instance.getStartedBy(),
                instance.getStartedAt(),
                instance.getApprovalCompletedAt()
        ))));
    }

    private int countStatus(List<WorkflowTask> tasks, WorkflowTaskStatus status) {
        return (int) tasks.stream().filter(task -> task.getTaskStatus() == status).count();
    }

    private int countCompleted(List<WorkflowTask> tasks) {
        return countStatus(tasks, WorkflowTaskStatus.DONE);
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private String operatorId(WorkflowTaskActionRequest request) {
        if (request.operatorId() != null && !request.operatorId().isBlank()) {
            return request.operatorId();
        }
        return CurrentUserContext.currentUser()
                .map(user -> user.userId())
                .filter(userId -> !userId.isBlank())
                .orElse("system");
    }

    private Instant operatedAt(WorkflowTaskActionRequest request) {
        return request.operatedAt() == null ? Instant.now() : request.operatedAt();
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new PlatformException(message);
        }
        return value;
    }

    private WorkflowRouteInstanceDao requireRouteDao() {
        if (routeInstanceDao == null) {
            throw new PlatformException("workflow route dao is required");
        }
        return routeInstanceDao;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean sameText(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record RollbackTarget(WorkflowNodeInstance node, List<WorkflowRouteInstance> routes,
                                  List<WorkflowNodeInstance> intermediateNodes,
                                  RollbackBranchDomain branchDomain) {

        static RollbackTarget linear(WorkflowNodeInstance node, List<WorkflowRouteInstance> routes,
                                     List<WorkflowNodeInstance> intermediateNodes) {
            return new RollbackTarget(node, routes, intermediateNodes, null);
        }

        static RollbackTarget crossBranch(WorkflowNodeInstance node, List<WorkflowRouteInstance> routes,
                                          List<WorkflowNodeInstance> intermediateNodes,
                                          RollbackBranchDomain branchDomain) {
            return new RollbackTarget(node, routes, intermediateNodes, branchDomain);
        }

        boolean crossBranchDomain() {
            return branchDomain != null;
        }

        List<WorkflowNodeInstance> nodesToReset() {
            if (branchDomain == null) {
                return intermediateNodes;
            }
            Map<String, WorkflowNodeInstance> result = new LinkedHashMap<>();
            addNodes(result, intermediateNodes);
            addNodes(result, branchDomain.nodes());
            return new ArrayList<>(result.values());
        }

        List<WorkflowRouteInstance> routesToReset() {
            Map<String, WorkflowRouteInstance> result = new LinkedHashMap<>();
            addRoutes(result, routes);
            if (branchDomain != null) {
                addRoutes(result, branchDomain.routes());
            }
            return new ArrayList<>(result.values());
        }

        boolean containsResetNodeId(String nodeInstanceId) {
            if (nodeInstanceId == null || nodeInstanceId.isBlank()) {
                return false;
            }
            return nodesToReset().stream().anyMatch(node -> nodeInstanceId.equals(node.getId()));
        }

        private static void addRoutes(Map<String, WorkflowRouteInstance> result, List<WorkflowRouteInstance> routes) {
            for (WorkflowRouteInstance route : routes == null ? List.<WorkflowRouteInstance>of() : routes) {
                String key = route.getId() != null && !route.getId().isBlank()
                        ? route.getId() : route.getRouteRunId();
                result.putIfAbsent(key, route);
            }
        }

        private static void addNodes(Map<String, WorkflowNodeInstance> result, List<WorkflowNodeInstance> nodes) {
            for (WorkflowNodeInstance node : nodes == null ? List.<WorkflowNodeInstance>of() : nodes) {
                String key = node.getId() != null && !node.getId().isBlank() ? node.getId() : node.getNodeRunId();
                result.putIfAbsent(key, node);
            }
        }
    }

    private record RollbackBranchDomain(List<WorkflowNodeInstance> nodes, List<WorkflowRouteInstance> routes) {
    }

    private record AddSignSegmentPlan(List<WorkflowNodeInstance> nodes,
                                      List<WorkflowRouteInstance> routes,
                                      List<String> addedNodeKeys) {
    }

    private record EditableAddSignSegment(List<WorkflowNodeInstance> nodes,
                                          List<WorkflowRouteInstance> routes,
                                          Set<String> nodeKeys) {

        Set<String> nodeIds() {
            return nodes.stream()
                    .map(WorkflowNodeInstance::getId)
                    .collect(java.util.stream.Collectors.toSet());
        }

        List<String> routeIds() {
            return routes.stream()
                    .map(WorkflowRouteInstance::getId)
                    .filter(id -> id != null && !id.isBlank())
                    .toList();
        }

        List<WorkflowRouteInstance> restoreOriginalOutgoingRoutes(String sourceNodeKey) {
            List<WorkflowRouteInstance> exitRoutes = routes.stream()
                    .filter(route -> nodeKeys.contains(route.getSourceNodeKey()))
                    .filter(route -> !nodeKeys.contains(route.getTargetNodeKey()))
                    .toList();
            if (exitRoutes.size() != 1) {
                throw new PlatformException("workflow add sign replace only supports single editable exit route: "
                        + sourceNodeKey);
            }
            WorkflowRouteInstance exit = exitRoutes.getFirst();
            WorkflowRouteInstance restored = new WorkflowRouteInstance();
            restored.setTenantId(exit.getTenantId());
            restored.setInstanceId(exit.getInstanceId());
            restored.setRouteKey(sourceNodeKey + "-" + exit.getTargetNodeKey() + ":replace");
            restored.setRouteRunId(restored.getRouteKey() + ":1");
            restored.setSourceNodeKey(sourceNodeKey);
            restored.setTargetNodeKey(exit.getTargetNodeKey());
            restored.setRouteStatus(WorkflowRouteStatus.CANDIDATE);
            restored.setDefaultRoute(exit.getDefaultRoute());
            return List.of(restored);
        }
    }
}
