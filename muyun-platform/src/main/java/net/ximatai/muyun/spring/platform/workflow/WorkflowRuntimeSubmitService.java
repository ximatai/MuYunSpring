package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WorkflowRuntimeSubmitService {
    private final WorkflowSubmitDraftService submitDraftService;
    private final WorkflowInstanceService instanceService;
    private final WorkflowInstanceDao instanceDao;
    private final WorkflowNodeInstanceDao nodeInstanceDao;
    private final WorkflowRouteInstanceDao routeInstanceDao;
    private final WorkflowTaskDao taskDao;
    private final WorkflowEventDao eventDao;
    private final WorkflowRuntimePluginDispatcher pluginDispatcher;
    private final ObjectProvider<WorkflowArchiveService> archives;
    private final WorkflowRuntimeEventFactory eventFactory = new WorkflowRuntimeEventFactory();

    @Autowired
    public WorkflowRuntimeSubmitService(WorkflowSubmitDraftService submitDraftService,
                                        WorkflowInstanceService instanceService,
                                        WorkflowInstanceDao instanceDao,
                                        WorkflowNodeInstanceDao nodeInstanceDao,
                                        WorkflowRouteInstanceDao routeInstanceDao,
                                        WorkflowTaskDao taskDao,
                                        WorkflowEventDao eventDao,
                                        WorkflowRuntimePluginDispatcher pluginDispatcher,
                                        ObjectProvider<WorkflowArchiveService> archives) {
        this.archives = java.util.Objects.requireNonNull(archives, "archives");
        this.submitDraftService = submitDraftService;
        this.instanceService = instanceService;
        this.instanceDao = instanceDao;
        this.nodeInstanceDao = nodeInstanceDao;
        this.routeInstanceDao = routeInstanceDao;
        this.taskDao = taskDao;
        this.eventDao = eventDao;
        this.pluginDispatcher = java.util.Objects.requireNonNull(pluginDispatcher, "pluginDispatcher");
    }

    @Transactional
    public WorkflowSubmitDraft submit(WorkflowDefinition definition,
                                      WorkflowVersion version,
                                      java.util.List<WorkflowNodeDefinition> nodeDefinitions,
                                      java.util.List<WorkflowLinkDefinition> linkDefinitions,
                                      String recordId,
                                      String operatorId,
                                      Instant operatedAt) {
        return submit(definition, version, nodeDefinitions, linkDefinitions, recordId, operatorId, operatedAt,
                null, null);
    }

    @Transactional
    public WorkflowSubmitDraft submit(WorkflowDefinition definition,
                                      WorkflowVersion version,
                                      java.util.List<WorkflowNodeDefinition> nodeDefinitions,
                                      java.util.List<WorkflowLinkDefinition> linkDefinitions,
                                      String recordId,
                                      String operatorId,
                                      Instant operatedAt,
                                      String selectedRouteKey,
                                      String selectedReason) {
        return submit(definition, version, nodeDefinitions, linkDefinitions, recordId, operatorId, operatedAt,
                selectedRouteKey, selectedReason, List.of());
    }

    @Transactional
    public WorkflowSubmitDraft submit(WorkflowDefinition definition,
                                      WorkflowVersion version,
                                      java.util.List<WorkflowNodeDefinition> nodeDefinitions,
                                      java.util.List<WorkflowLinkDefinition> linkDefinitions,
                                      String recordId,
                                      String operatorId,
                                      Instant operatedAt,
                                      String selectedRouteKey,
                                      String selectedReason,
                                      List<WorkflowManualRouteSelection> manualRouteSelections) {
        WorkflowSubmitDraft draft = submitDraftService.build(definition, version, nodeDefinitions, linkDefinitions,
                recordId, null, operatorId, operatedAt, selectedRouteKey, selectedReason, manualRouteSelections);
        dispatchSubmit(draft, WorkflowRuntimePluginEventType.BEFORE_SUBMIT, operatorId);
        persist(draft, operatedAt);
        dispatchSubmit(draft, WorkflowRuntimePluginEventType.AFTER_SUBMIT, operatorId);
        return draft;
    }

    public WorkflowSubmitDraft preview(WorkflowDefinition definition,
                                       WorkflowVersion version,
                                       java.util.List<WorkflowNodeDefinition> nodeDefinitions,
                                       java.util.List<WorkflowLinkDefinition> linkDefinitions,
                                       String recordId,
                                       String operatorId,
                                       Instant operatedAt,
                                       String selectedRouteKey,
                                       String selectedReason,
                                       List<WorkflowManualRouteSelection> manualRouteSelections) {
        return submitDraftService.build(definition, version, nodeDefinitions, linkDefinitions,
                recordId, null, operatorId, operatedAt, selectedRouteKey, selectedReason, manualRouteSelections);
    }

    public WorkflowSubmitDraft preview(WorkflowDefinition definition,
                                       WorkflowVersion version,
                                       java.util.List<WorkflowNodeDefinition> nodeDefinitions,
                                       java.util.List<WorkflowLinkDefinition> linkDefinitions,
                                       String recordId,
                                       String authOrgId,
                                       String operatorId,
                                       Instant operatedAt,
                                       String selectedRouteKey,
                                       String selectedReason,
                                       List<WorkflowManualRouteSelection> manualRouteSelections) {
        return submitDraftService.build(definition, version, nodeDefinitions, linkDefinitions,
                recordId, authOrgId, operatorId, operatedAt, selectedRouteKey, selectedReason,
                manualRouteSelections);
    }

    @Transactional
    public WorkflowSubmitDraft submit(WorkflowDefinition definition,
                                      WorkflowVersion version,
                                      java.util.List<WorkflowNodeDefinition> nodeDefinitions,
                                      java.util.List<WorkflowLinkDefinition> linkDefinitions,
                                      String recordId,
                                      String authOrgId,
                                      String operatorId,
                                      Instant operatedAt) {
        return submit(definition, version, nodeDefinitions, linkDefinitions, recordId, authOrgId, operatorId,
                operatedAt, null, null);
    }

    @Transactional
    public WorkflowSubmitDraft submit(WorkflowDefinition definition,
                                      WorkflowVersion version,
                                      java.util.List<WorkflowNodeDefinition> nodeDefinitions,
                                      java.util.List<WorkflowLinkDefinition> linkDefinitions,
                                      String recordId,
                                      String authOrgId,
                                      String operatorId,
                                      Instant operatedAt,
                                      String selectedRouteKey,
                                      String selectedReason) {
        return submit(definition, version, nodeDefinitions, linkDefinitions, recordId, authOrgId, operatorId,
                operatedAt, selectedRouteKey, selectedReason, List.of());
    }

    @Transactional
    public WorkflowSubmitDraft submit(WorkflowDefinition definition,
                                      WorkflowVersion version,
                                      java.util.List<WorkflowNodeDefinition> nodeDefinitions,
                                      java.util.List<WorkflowLinkDefinition> linkDefinitions,
                                      String recordId,
                                      String authOrgId,
                                      String operatorId,
                                      Instant operatedAt,
                                      String selectedRouteKey,
                                      String selectedReason,
                                      List<WorkflowManualRouteSelection> manualRouteSelections) {
        WorkflowSubmitDraft draft = submitDraftService.build(definition, version, nodeDefinitions, linkDefinitions,
                recordId, authOrgId, operatorId, operatedAt, selectedRouteKey, selectedReason,
                manualRouteSelections);
        dispatchSubmit(draft, WorkflowRuntimePluginEventType.BEFORE_SUBMIT, operatorId);
        persist(draft, operatedAt);
        dispatchSubmit(draft, WorkflowRuntimePluginEventType.AFTER_SUBMIT, operatorId);
        return draft;
    }

    @Transactional
    public void persist(WorkflowSubmitDraft draft, Instant operatedAt) {
        Instant now = operatedAt == null ? Instant.now() : operatedAt;
        prepareRestart(draft.instance(), now);
        prepareInsert(draft.instance(), now);
        instanceService.beforeInsert(draft.instance());
        instanceDao.insert(draft.instance());
        draft.nodes().forEach(node -> {
            prepareInsert(node, now);
            nodeInstanceDao.insert(node);
        });
        draft.routes().forEach(route -> {
            prepareInsert(route, now);
            routeInstanceDao.insert(route);
        });
        draft.tasks().forEach(task -> {
            prepareInsert(task, now);
            taskDao.insert(task);
        });
        draft.events().forEach(event -> {
            prepareInsert(event, now);
            eventDao.insert(event);
        });
    }

    /** A restart closes the rejected round in the same transaction as the replacement submission. */
    private void prepareRestart(WorkflowInstance replacement, Instant now) {
        if (!Boolean.TRUE.equals(replacement.getApprovalEnabled())) return;
        WorkflowMutationLock.record(replacement.getModuleAlias(), replacement.getRecordId());
        var all = new net.ximatai.muyun.database.core.orm.PageRequest(0, Integer.MAX_VALUE);
        var previous = instanceDao.query(WorkflowTenantScope.criteria().eq("moduleAlias", replacement.getModuleAlias())
                .eq("recordId", replacement.getRecordId()).eq("approvalEnabled", true), all);
        for (var pointer : previous) {
            WorkflowMutationLock.instance(pointer.getId());
            var instance = WorkflowTenantScope.visible(instanceDao.findById(pointer.getId()));
            if (instance == null) continue;
            if (instance.getInstanceStatus() == WorkflowInstanceStatus.RUNNING || instance.getApprovalStatus() == WorkflowApprovalStatus.APPROVED)
                throw new net.ximatai.muyun.spring.common.exception.PlatformException("approval workflow already running or approved for record: " + replacement.getRecordId());
            if (instance.getInstanceStatus() != WorkflowInstanceStatus.REJECTED) continue;
            if (instance.getRejectResubmitMode() != WorkflowRejectResubmitMode.RESTART)
                throw new net.ximatai.muyun.spring.common.exception.PlatformException("请通过当前重提任务返回驳回人");
            if (!java.util.Objects.equals(instance.getStartedBy(), replacement.getStartedBy()))
                throw new net.ximatai.muyun.spring.common.exception.PlatformException("只有原发起人可以重新发起被驳回的申请");
            var pending = taskDao.query(WorkflowTenantScope.criteria().eq("instanceId", instance.getId())
                    .eq("taskKind", WorkflowTaskKind.RESUBMIT).eq("taskStatus", WorkflowTaskStatus.TODO), all);
            if (pending.size() != 1 || !java.util.Objects.equals(pending.getFirst().getAssigneeId(), replacement.getStartedBy()))
                throw new net.ximatai.muyun.spring.common.exception.PlatformException("重新发起的申请缺少有效重提任务");
            var task = pending.getFirst();
            task.setTaskStatus(WorkflowTaskStatus.DONE); task.setActualProcessorId(replacement.getStartedBy());
            task.setDecision("resubmit_restart"); task.setCompletedAt(now); task.setResultMessage("重新发起审批");
            var expected = task.getVersion();
            EntityLifecycle.prepareUpdate(task, now, EntityLifecycle.nextVersion(expected));
            if (taskDao.updateByIdAndVersion(task, expected) == 0) throw new net.ximatai.muyun.spring.ability.OptimisticLockException("restart task version conflict");
            var event = eventFactory.taskResubmitted(instance, task, replacement.getStartedBy(), "重新发起审批", now);
            prepareInsert(event, now); eventDao.insert(event);
            replacement.setPreviousInstanceId(instance.getId());
            instance.setLastActionCode("resubmit_restart"); instance.setLastActionReason("重新发起审批");
            instance.setLastOperatorId(replacement.getStartedBy()); instance.setLastOperatedAt(now);
            for (var leftover : taskDao.query(WorkflowTenantScope.criteria().eq("instanceId", instance.getId()).eq("taskStatus", WorkflowTaskStatus.TODO), all)) {
                leftover.setTaskStatus(WorkflowTaskStatus.CANCELED); leftover.setDecision("resubmit_restart");
                leftover.setActualProcessorId(replacement.getStartedBy()); leftover.setCompletedAt(now);
                var leftoverVersion = leftover.getVersion();
                EntityLifecycle.prepareUpdate(leftover, now, EntityLifecycle.nextVersion(leftoverVersion));
                if (taskDao.updateByIdAndVersion(leftover, leftoverVersion) == 0)
                    throw new net.ximatai.muyun.spring.ability.OptimisticLockException("restart task version conflict");
            }
            archives.getObject().archiveCurrentInstance(instance, WorkflowArchiveReason.RESTARTED, now);
        }
    }

    private void prepareInsert(net.ximatai.muyun.spring.common.model.contract.EntityContract entity, Instant now) {
        EntityLifecycle.prepareInsert(entity, now);
    }

    private void dispatchSubmit(WorkflowSubmitDraft draft, WorkflowRuntimePluginEventType eventType,
                                String operatorId) {
        if (draft == null || draft.instance() == null) {
            return;
        }
        Map<String, WorkflowNodeInstance> nodesById = new LinkedHashMap<>();
        draft.nodes().forEach(node -> nodesById.put(node.getId(), node));
        if (!draft.tasks().isEmpty()) {
            draft.tasks().forEach(task -> dispatch(draft.instance(), nodesById.get(task.getNodeInstanceId()), task,
                    eventType, "submit", operatorId, null, null, null, null));
            return;
        }
        if (!draft.nodes().isEmpty()) {
            draft.nodes().stream()
                    .filter(node -> node.getNodeStatus() == WorkflowNodeStatus.ACTIVE)
                    .forEach(node -> dispatch(draft.instance(), node, null, eventType, "submit", operatorId,
                            null, null, null, null));
            return;
        }
        dispatch(draft.instance(), null, null, eventType, "submit", operatorId, null, null, null, null);
    }

    private void dispatch(WorkflowInstance instance,
                          WorkflowNodeInstance node,
                          WorkflowTask task,
                          WorkflowRuntimePluginEventType eventType,
                          String actionCode,
                          String operatorId,
                          String targetAssigneeId,
                          String rollbackTargetNodeKey,
                          WorkflowRuntimeTerminateMode terminateMode,
                          String reason) {
        pluginDispatcher.dispatch(new WorkflowRuntimePluginContext(eventType, actionCode,
                instance.getModuleAlias(), instance.getRecordId(), instance.getId(),
                node == null ? null : node.getNodeKey(), task == null ? null : task.getId(),
                operatorId, targetAssigneeId, rollbackTargetNodeKey, terminateMode, reason, instance, node, task));
    }
}
