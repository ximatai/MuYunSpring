package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.platform.ModuleRecordFacts;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class WorkflowRuntimeProgressionService {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final WorkflowInstanceDao instanceDao;
    private final WorkflowNodeInstanceDao nodeDao;
    private final WorkflowRouteInstanceDao routeDao;
    private final WorkflowTaskDao taskDao;
    private final WorkflowEventDao eventDao;
    private final WorkflowRuntimeActivationService activationService;
    private final WorkflowInstanceStateService instanceStateService;
    private final WorkflowNodeInstanceStateService nodeStateService;
    private final WorkflowRouteInstanceStateService routeStateService;
    private final WorkflowRouteRuntimeService routeRuntimeService;
    private final WorkflowRuntimeTaskFactory taskFactory;
    private final WorkflowRuntimeEventFactory eventFactory;
    private final ModuleRecordFacts facts;
    private final Optional<WorkflowApprovalSummaryWriter> approvalSummaryWriter;
    private final WorkflowManualRouteSelectionPolicy manualRouteSelectionPolicy = new WorkflowManualRouteSelectionPolicy();

    public WorkflowRuntimeProgressionService(WorkflowInstanceDao instanceDao,
                                             WorkflowNodeInstanceDao nodeDao,
                                             WorkflowRouteInstanceDao routeDao,
                                             WorkflowTaskDao taskDao,
                                             WorkflowEventDao eventDao,
                                             WorkflowRuntimeActivationService activationService,
                                             WorkflowInstanceStateService instanceStateService,
                                             WorkflowNodeInstanceStateService nodeStateService,
                                             WorkflowRouteInstanceStateService routeStateService,
                                             WorkflowRouteRuntimeService routeRuntimeService,
                                             WorkflowRuntimeTaskFactory taskFactory,
                                             WorkflowRuntimeEventFactory eventFactory,
                                             Optional<WorkflowApprovalSummaryWriter> approvalSummaryWriter, ModuleRecordFacts facts) {
        this.instanceDao = instanceDao;
        this.nodeDao = nodeDao;
        this.routeDao = routeDao;
        this.taskDao = taskDao;
        this.eventDao = eventDao;
        this.activationService = activationService;
        this.instanceStateService = instanceStateService;
        this.nodeStateService = nodeStateService;
        this.routeStateService = routeStateService;
        this.routeRuntimeService = routeRuntimeService;
        this.taskFactory = taskFactory;
        this.eventFactory = eventFactory;
        this.approvalSummaryWriter = approvalSummaryWriter;
        this.facts = facts;
    }

    @Transactional
    public WorkflowProgressionResult advanceFromNode(String instanceId, String completedNodeKey,
                                                     String operatorId, Instant operatedAt) {
        return advanceFromNode(instanceId, completedNodeKey, operatorId, operatedAt, (String) null);
    }

    @Transactional
    public WorkflowProgressionResult advanceFromNode(String instanceId, String completedNodeKey,
                                                     String operatorId, Instant operatedAt,
                                                     String selectedRouteKey) {
        return advanceFromNode(instanceId, completedNodeKey, operatorId, operatedAt, selectedRouteKey, null);
    }

    @Transactional
    public WorkflowProgressionResult advanceFromNode(String instanceId, String completedNodeKey,
                                                     String operatorId, Instant operatedAt,
                                                     String selectedRouteKey,
                                                     String selectedReason) {
        return advanceFromNode(instanceId, completedNodeKey, operatorId, operatedAt, selectedRouteKey,
                selectedReason, List.of());
    }

    @Transactional
    public WorkflowProgressionResult advanceFromNode(String instanceId, String completedNodeKey,
                                                     String operatorId, Instant operatedAt,
                                                     List<WorkflowManualRouteSelection> manualRouteSelections) {
        return advanceFromNode(instanceId, completedNodeKey, operatorId, operatedAt, null, null,
                manualRouteSelections);
    }

    @Transactional
    public WorkflowProgressionResult advanceFromNode(String instanceId, String completedNodeKey,
                                                     String operatorId, Instant operatedAt,
                                                     String selectedRouteKey,
                                                     String selectedReason,
                                                     List<WorkflowManualRouteSelection> manualRouteSelections) {
        WorkflowInstance instance = requireInstance(instanceId);
        if (instance.getInstanceStatus() != WorkflowInstanceStatus.RUNNING) {
            throw new PlatformException("workflow instance is not running: " + instanceId);
        }
        Instant now = operatedAt == null ? Instant.now() : operatedAt;
        String selectedKey = manualRouteSelectionPolicy.selectedRouteKeyForBranch(completedNodeKey,
                manualRouteSelections, selectedRouteKey);
        List<WorkflowNodeInstance> nodes = nodes(instanceId);
        List<WorkflowRouteInstance> routes = routes(instanceId);
        List<WorkflowTask> tasks = tasks(instanceId);
        WorkflowRuntimeGraph graph = graph(nodes, routes);
        List<WorkflowRouteInstance> selectedInitialRoutes = selectOutgoingRoutes(routes, nodes, graph,
                completedNodeKey, selectedKey);
        if (selectedInitialRoutes.isEmpty()) {
            return WorkflowProgressionResult.empty(instance);
        }
        manualRouteSelectionPolicy.requireCompletedBranchSelection(instance, nodes, tasks, completedNodeKey,
                selectedInitialRoutes, manualRouteSelections, selectedRouteKey, selectedReason, operatorId);
        var businessFacts = routes.stream().anyMatch(route -> route.getConditionExpression() != null && !route.getConditionExpression().isBlank())
                ? facts.read(instance.getModuleAlias(), instance.getRecordId()) : java.util.Map.<String, Object>of();
        Map<String, Set<String>> selectedRouteKeysByBranch =
                manualRouteSelectionPolicy.selectedRouteKeysByProgressionBranch(routes, nodes, graph, instance,
                        tasks, completedNodeKey, selectedInitialRoutes, manualRouteSelections, selectedRouteKey,
                        selectedReason, operatorId, businessFacts);

        List<WorkflowRouteInstance> droppedRoutes = dropUnselectedOutgoingRoutes(routes, completedNodeKey,
                selectedInitialRoutes, operatorId, now);
        List<WorkflowEvent> events = new ArrayList<>();
        for (WorkflowRouteInstance route : selectedInitialRoutes) {
            WorkflowRouteReason reason = routeReason(route, selectedKey);
            routeRuntimeService.effectiveRoute(route, reason, operatorId, now,
                    selectedReasonForRoute(route, reason, manualRouteSelections, selectedKey, selectedReason));
            events.add(eventFactory.routeSelected(instance, route, operatorId, now));
        }
        for (WorkflowRouteInstance route : droppedRoutes) {
            events.add(eventFactory.routeDropped(instance, route, operatorId, now));
        }
        Set<String> passedConvergeNodeKeys = new LinkedHashSet<>();
        WorkflowActivationResult activation = new WorkflowActivationExecutor(activationService, nodeStateService,
                routeStateService, routeRuntimeService).execute(new WorkflowActivationRequest(
                graph,
                selectedInitialRoutes.stream()
                        .map(route -> new WorkflowActivationTarget(route.getTargetNodeKey(), route.getId()))
                        .toList(),
                selectedRouteKeysByBranch,
                passedConvergeNodeKeys,
                512, businessFacts
        ), nodes, routes, operatorId, now);
        applyManualBranchSelection(routes, selectedRouteKeysByBranch, manualRouteSelections, selectedRouteKey,
                selectedReason, operatorId, now);
        instanceStateService.applyActivation(instance, activation, now);
        nodeStateService.applyActivation(nodes, activation, now);
        routeStateService.applyActivation(routes, activation, operatorId, now);
        cancelDroppedPathTasks(instance, nodes, routes, tasks, operatorId, now, events);
        var activeKeys = nodes.stream().filter(node -> node.getNodeStatus() == WorkflowNodeStatus.ACTIVE)
                .map(WorkflowNodeInstance::getNodeKey).toList();
        instance.setCurrentNodeKeys(String.join(",", activeKeys));
        if (!activeKeys.isEmpty()) { instance.setInstanceStatus(WorkflowInstanceStatus.RUNNING); instance.setCompletedAt(null); }
        WorkflowRuntimeTaskDraft taskDraft = taskFactory.createBlockingTasks(instance, nodes, activation, operatorId, now);
        events.addAll(taskDraft.events());
        if (instance.getInstanceStatus() == WorkflowInstanceStatus.COMPLETED) {
            events.add(eventFactory.instanceCompleted(instance, operatorId, now));
        }
        if (activation.approvalCompleted()) {
            events.add(eventFactory.approvalCompleted(instance, operatorId, now));
            writeApprovalSummary(instance, nodes.stream().anyMatch(node -> completedNodeKey.equals(node.getNodeKey()) && node.getNodeType() == WorkflowNodeType.TASK) ? "complete" : "approve");
        }

        persist(instance, nodes, routes, taskDraft.tasks(), events, now);
        return new WorkflowProgressionResult(instance, activatedNodes(nodes, activation), selectedInitialRoutes,
                droppedRoutes, taskDraft.tasks(), events, activation);
    }

    private void cancelDroppedPathTasks(WorkflowInstance instance, List<WorkflowNodeInstance> nodes,
                                         List<WorkflowRouteInstance> routes, List<WorkflowTask> tasks,
                                         String operator, Instant now, List<WorkflowEvent> events) {
        var dropped = routes.stream().filter(route -> route.getRouteStatus() == WorkflowRouteStatus.DROPPED)
                .map(WorkflowRouteInstance::getId).collect(Collectors.toSet());
        if (dropped.isEmpty()) return;
        Set<String> droppedNodes = new LinkedHashSet<>();
        for (var route : routes) {
            String path = route.getPathRouteId();
            Set<String> visited = new java.util.HashSet<>();
            while (path != null && visited.add(path)) {
                if (dropped.contains(path)) { droppedNodes.add(route.getTargetNodeKey()); break; }
                String current = path;
                path = routes.stream().filter(item -> current.equals(item.getId())).map(WorkflowRouteInstance::getParentRouteId)
                        .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            }
        }
        for (var node : nodes) {
            if (!droppedNodes.contains(node.getNodeKey()) || node.getNodeType() == WorkflowNodeType.CONVERGE) continue;
            if (node.getNodeStatus() == WorkflowNodeStatus.ACTIVE || node.getNodeStatus() == WorkflowNodeStatus.WAITING) {
                node.setNodeStatus(WorkflowNodeStatus.SKIPPED); node.setCompletedAt(now);
            }
                for (var task : tasks) {
                    if (node.getId().equals(task.getNodeInstanceId()) && task.getTaskStatus() == WorkflowTaskStatus.TODO) {
                        task.setTaskStatus(WorkflowTaskStatus.CANCELED); task.setCompletedAt(now);
                        task.setDecision("route_dropped"); task.setResultMessage("汇聚已通过，当前分支停止办理");
                        Integer version = task.getVersion(); EntityLifecycle.prepareUpdate(task, now);
                        if (taskDao.updateByIdAndVersion(task, version) == 0) throw new OptimisticLockException("workflow task version conflict");
                        events.add(eventFactory.taskCompleted(instance, task, "route_dropped", operator, task.getResultMessage(), now));
                    }
                }
        }
    }

    private void persist(WorkflowInstance instance,
                         List<WorkflowNodeInstance> nodes,
                         List<WorkflowRouteInstance> routes,
                         List<WorkflowTask> tasks,
                         List<WorkflowEvent> events,
                         Instant now) {
        updateInstance(instance, now);
        nodes.forEach(node -> updateNode(node, now));
        routes.forEach(route -> updateRoute(route, now));
        tasks.forEach(task -> {
            EntityLifecycle.prepareInsert(task, now);
            taskDao.insert(task);
        });
        events.forEach(event -> {
            EntityLifecycle.prepareInsert(event, now);
            eventDao.insert(event);
        });
    }

    private void writeApprovalSummary(WorkflowInstance instance, String actionCode) {
        if (!Boolean.TRUE.equals(instance.getApprovalEnabled())) {
            return;
        }
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

    private WorkflowRuntimeGraph graph(List<WorkflowNodeInstance> nodes, List<WorkflowRouteInstance> routes) {
        return WorkflowManualBranchFrontier.frozenGraph(nodes, routes);
    }

    private List<WorkflowRouteInstance> selectOutgoingRoutes(List<WorkflowRouteInstance> routes,
                                                             List<WorkflowNodeInstance> nodes,
                                                             WorkflowRuntimeGraph graph,
                                                             String nodeKey,
                                                             String selectedRouteKey) {
        List<WorkflowRouteInstance> outgoing = routes.stream()
                .filter(route -> nodeKey.equals(route.getSourceNodeKey()))
                .filter(route -> route.getRouteStatus() == WorkflowRouteStatus.CANDIDATE)
                .toList();
        if (selectedRouteKey != null) {
            List<WorkflowRouteInstance> selected = outgoing.stream()
                    .filter(route -> selectedRouteKey.equals(route.getRouteKey()))
                    .toList();
            if (!selected.isEmpty()) {
                return selected;
            }
        }
        List<WorkflowRouteInstance> defaults = outgoing.stream()
                .filter(route -> Boolean.TRUE.equals(route.getDefaultRoute()))
                .toList();
        List<WorkflowRouteInstance> defaultSelection = defaults.isEmpty() ? outgoing : defaults;
        if (selectedRouteKey != null
                && !canUseSelectedRouteForReachableBranch(routes, nodes, graph, defaultSelection, selectedRouteKey)) {
            throw new PlatformException("workflow selected route is not candidate outgoing route");
        }
        return defaultSelection;
    }

    private boolean canUseSelectedRouteForReachableBranch(List<WorkflowRouteInstance> routes,
                                                          List<WorkflowNodeInstance> nodes,
                                                          WorkflowRuntimeGraph graph,
                                                          List<WorkflowRouteInstance> selectedInitialRoutes,
                                                          String selectedRouteKey) {
        Set<String> branchNodeKeys = reachableBranchNodeKeys(nodes, graph, selectedInitialRoutes);
        return routes.stream()
                .anyMatch(route -> route.getRouteStatus() == WorkflowRouteStatus.CANDIDATE
                        && selectedRouteKey.equals(route.getRouteKey())
                        && branchNodeKeys.contains(route.getSourceNodeKey()));
    }

    private Set<String> reachableBranchNodeKeys(List<WorkflowNodeInstance> nodes,
                                                WorkflowRuntimeGraph graph,
                                                List<WorkflowRouteInstance> selectedInitialRoutes) {
        Map<String, WorkflowNodeInstance> nodesByKey = nodes.stream()
                .collect(Collectors.toMap(WorkflowNodeInstance::getNodeKey, Function.identity(), (left, right) -> left));
        ArrayList<String> queue = selectedInitialRoutes.stream()
                .map(WorkflowRouteInstance::getTargetNodeKey)
                .collect(Collectors.toCollection(ArrayList::new));
        Set<String> visited = new LinkedHashSet<>();
        Set<String> branchNodeKeys = new LinkedHashSet<>();
        for (int index = 0; index < queue.size(); index++) {
            String nodeKey = queue.get(index);
            if (!visited.add(nodeKey)) {
                continue;
            }
            WorkflowNodeInstance node = nodesByKey.get(nodeKey);
            if (node == null) {
                continue;
            }
            if (node.getNodeType() == WorkflowNodeType.BRANCH) {
                branchNodeKeys.add(nodeKey);
                continue;
            }
            if (node.getNodeType() == WorkflowNodeType.APPROVAL || node.getNodeType() == WorkflowNodeType.TASK) {
                continue;
            }
            graph.outgoing(nodeKey).forEach(link -> queue.add(link.getTargetNodeKey()));
        }
        return branchNodeKeys;
    }

    private void applyManualBranchSelection(List<WorkflowRouteInstance> routes,
                                            Map<String, Set<String>> selectedRouteKeysByBranch,
                                            List<WorkflowManualRouteSelection> manualRouteSelections,
                                            String selectedRouteKey,
                                            String selectedReason,
                                            String operatorId,
                                            Instant now) {
        if (selectedRouteKeysByBranch.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Set<String>> entry : selectedRouteKeysByBranch.entrySet()) {
            for (WorkflowRouteInstance route : routes) {
                if (!entry.getKey().equals(route.getSourceNodeKey())
                        || (route.getRouteStatus() != WorkflowRouteStatus.CANDIDATE
                        && route.getRouteStatus() != WorkflowRouteStatus.EFFECTIVE
                        && route.getRouteStatus() != WorkflowRouteStatus.CLOSED
                        && route.getRouteStatus() != WorkflowRouteStatus.DROPPED)) {
                    continue;
                }
                if (entry.getValue().contains(route.getRouteKey())) {
                    routeRuntimeService.recordManualSelection(route, operatorId, now,
                            selectedReasonForRoute(route, WorkflowRouteReason.MANUAL_SELECTED,
                                    manualRouteSelections, selectedRouteKey, selectedReason));
                } else {
                    routeRuntimeService.ineffectiveRoute(route, WorkflowRouteReason.MANUAL_UNSELECTED,
                            operatorId, now);
                }
            }
        }
    }

    private List<WorkflowRouteInstance> dropUnselectedOutgoingRoutes(List<WorkflowRouteInstance> routes,
                                                                     String nodeKey,
                                                                     List<WorkflowRouteInstance> selected,
                                                                     String operatorId,
                                                                     Instant now) {
        Set<String> selectedIds = selected.stream().map(WorkflowRouteInstance::getId).collect(Collectors.toSet());
        List<WorkflowRouteInstance> dropped = new ArrayList<>();
        for (WorkflowRouteInstance route : routes) {
            if (!nodeKey.equals(route.getSourceNodeKey()) || route.getRouteStatus() != WorkflowRouteStatus.CANDIDATE
                    || selectedIds.contains(route.getId())) {
                continue;
            }
            routeRuntimeService.ineffectiveRoute(route, WorkflowRouteReason.MANUAL_UNSELECTED, operatorId, now);
            dropped.add(route);
        }
        return dropped;
    }

    private WorkflowRouteReason routeReason(WorkflowRouteInstance route, String selectedRouteKey) {
        return selectedRouteKey != null && selectedRouteKey.equals(route.getRouteKey())
                ? WorkflowRouteReason.MANUAL_SELECTED
                : Boolean.TRUE.equals(route.getDefaultRoute())
                ? WorkflowRouteReason.DEFAULT_SELECTED
                : WorkflowRouteReason.CONDITION_MATCHED;
    }

    private String selectedReasonForRoute(WorkflowRouteInstance route,
                                          WorkflowRouteReason reason,
                                          List<WorkflowManualRouteSelection> manualRouteSelections,
                                          String selectedRouteKey,
                                          String selectedReason) {
        if (reason != WorkflowRouteReason.MANUAL_SELECTED) {
            return null;
        }
        return manualRouteSelectionPolicy.selectedReasonForRoute(route, manualRouteSelections, selectedRouteKey,
                selectedReason);
    }

    private List<WorkflowNodeInstance> activatedNodes(List<WorkflowNodeInstance> nodes, WorkflowActivationResult activation) {
        Set<String> activated = Set.copyOf(activation.activatedNodeKeys());
        return nodes.stream().filter(node -> activated.contains(node.getNodeKey())).toList();
    }

    private WorkflowInstance requireInstance(String instanceId) {
        WorkflowInstance instance = instanceDao.findById(requireText(instanceId, "workflow instance id must not be blank"));
        if (instance == null) {
            throw new PlatformException("workflow instance not found: " + instanceId);
        }
        return instance;
    }

    private List<WorkflowNodeInstance> nodes(String instanceId) {
        return nodeDao.query(Criteria.of().eq("instanceId", instanceId), ALL);
    }

    private List<WorkflowRouteInstance> routes(String instanceId) {
        return routeDao.query(Criteria.of().eq("instanceId", instanceId), ALL);
    }

    private List<WorkflowTask> tasks(String instanceId) {
        return taskDao.query(Criteria.of().eq("instanceId", instanceId), ALL);
    }

    private void updateInstance(WorkflowInstance instance, Instant now) {
        Integer expectedVersion = instance.getVersion();
        EntityLifecycle.prepareUpdate(instance, now, EntityLifecycle.nextVersion(expectedVersion));
        int updated = instanceDao.updateByIdAndVersion(instance, expectedVersion);
        if (updated <= 0) {
            throw new OptimisticLockException("workflow instance version conflict: " + instance.getId());
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

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new PlatformException(message);
        }
        return value;
    }

    private String textOrNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
