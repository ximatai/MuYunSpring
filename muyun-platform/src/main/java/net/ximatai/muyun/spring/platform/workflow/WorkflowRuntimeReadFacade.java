package net.ximatai.muyun.spring.platform.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class WorkflowRuntimeReadFacade {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<WorkflowRouteStatus> MANUAL_BRANCH_CANDIDATE_STATUSES = EnumSet.of(
            WorkflowRouteStatus.CANDIDATE,
            WorkflowRouteStatus.EFFECTIVE,
            WorkflowRouteStatus.INEFFECTIVE);
    private static final String ROUTE_ALREADY_DECIDED = "ROUTE_ALREADY_DECIDED";

    private final WorkflowInstanceDao instanceDao;
    private final WorkflowTaskDao taskDao;
    private final WorkflowNodeInstanceDao nodeDao;
    private final WorkflowRouteInstanceDao routeDao;
    private final WorkflowEventDao eventDao;
    private final WorkflowTaskActionAvailabilityService availabilityService;
    private final WorkflowActionPolicyService actionPolicyService;
    private final WorkflowTaskAssignmentPolicyService assignmentPolicyService;
    private final WorkflowUserTitleResolver userTitleResolver;
    private final WorkflowRecordSummaryResolver recordSummaryResolver;
    private final WorkflowConditionService conditions;
    private final WorkflowManualBranchSelectorResolver manualBranchSelectorResolver =
            new WorkflowManualBranchSelectorResolver();

    public WorkflowRuntimeReadFacade(WorkflowInstanceDao instanceDao,
                                     WorkflowTaskDao taskDao,
                                     WorkflowNodeInstanceDao nodeDao,
                                     WorkflowRouteInstanceDao routeDao,
                                     WorkflowEventDao eventDao,
                                     WorkflowTaskActionAvailabilityService availabilityService, WorkflowConditionService conditions) {
        this(instanceDao, taskDao, nodeDao, routeDao, eventDao, availabilityService,
                new WorkflowActionPolicyService(), new WorkflowTaskAssignmentPolicyService(),
                WorkflowUserTitleResolver.NONE, conditions);
    }

    @Autowired
    public WorkflowRuntimeReadFacade(WorkflowInstanceDao instanceDao,
                                     WorkflowTaskDao taskDao,
                                     WorkflowNodeInstanceDao nodeDao,
                                     WorkflowRouteInstanceDao routeDao,
                                     WorkflowEventDao eventDao,
                                     WorkflowTaskActionAvailabilityService availabilityService,
                                     WorkflowActionPolicyService actionPolicyService,
                                     WorkflowTaskAssignmentPolicyService assignmentPolicyService,
                                     ObjectProvider<WorkflowUserTitleResolver> userTitleResolver,
                                     ObjectProvider<WorkflowRecordSummaryResolver> recordSummaryResolver,
                                     WorkflowConditionService conditions) {
        this(instanceDao, taskDao, nodeDao, routeDao, eventDao, availabilityService, actionPolicyService,
                assignmentPolicyService, userTitleResolver == null
                ? WorkflowUserTitleResolver.NONE
                : userTitleResolver.getIfAvailable(() -> WorkflowUserTitleResolver.NONE),
                recordSummaryResolver.getIfAvailable(() -> WorkflowRecordSummaryResolver.NONE), conditions);
    }

    public WorkflowRuntimeReadFacade(WorkflowInstanceDao instanceDao,
                                     WorkflowTaskDao taskDao,
                                     WorkflowNodeInstanceDao nodeDao,
                                     WorkflowRouteInstanceDao routeDao,
                                     WorkflowEventDao eventDao,
                                     WorkflowTaskActionAvailabilityService availabilityService,
                                     WorkflowActionPolicyService actionPolicyService,
                                     WorkflowTaskAssignmentPolicyService assignmentPolicyService,
                                     WorkflowUserTitleResolver userTitleResolver, WorkflowConditionService conditions) {
        this(instanceDao, taskDao, nodeDao, routeDao, eventDao, availabilityService, actionPolicyService,
                assignmentPolicyService, userTitleResolver, WorkflowRecordSummaryResolver.NONE, conditions);
    }

    public WorkflowRuntimeReadFacade(WorkflowInstanceDao instanceDao, WorkflowTaskDao taskDao,
                                    WorkflowNodeInstanceDao nodeDao, WorkflowRouteInstanceDao routeDao,
                                    WorkflowEventDao eventDao, WorkflowTaskActionAvailabilityService availabilityService,
                                    WorkflowActionPolicyService actionPolicyService,
                                    WorkflowTaskAssignmentPolicyService assignmentPolicyService,
                                    WorkflowUserTitleResolver userTitleResolver,
                                    WorkflowRecordSummaryResolver recordSummaryResolver, WorkflowConditionService conditions) {
        this.conditions = java.util.Objects.requireNonNull(conditions);
        this.instanceDao = instanceDao;
        this.taskDao = taskDao;
        this.nodeDao = nodeDao;
        this.routeDao = routeDao;
        this.eventDao = eventDao;
        this.availabilityService = availabilityService;
        this.actionPolicyService = actionPolicyService == null
                ? new WorkflowActionPolicyService()
                : actionPolicyService;
        this.assignmentPolicyService = assignmentPolicyService == null
                ? new WorkflowTaskAssignmentPolicyService()
                : assignmentPolicyService;
        this.userTitleResolver = userTitleResolver == null ? WorkflowUserTitleResolver.NONE : userTitleResolver;
        this.recordSummaryResolver = java.util.Objects.requireNonNull(recordSummaryResolver);
    }

    public WorkflowRuntimeReadFacade(WorkflowInstanceDao instanceDao,
                                     WorkflowTaskDao taskDao,
                                     WorkflowNodeInstanceDao nodeDao,
                                     WorkflowRouteInstanceDao routeDao,
                                     WorkflowEventDao eventDao,
                                     WorkflowTaskActionAvailabilityService availabilityService,
                                     WorkflowActionPolicyService actionPolicyService, WorkflowConditionService conditions) {
        this(instanceDao, taskDao, nodeDao, routeDao, eventDao, availabilityService, actionPolicyService,
                new WorkflowTaskAssignmentPolicyService(), WorkflowUserTitleResolver.NONE, conditions);
    }

    public WorkflowRuntimeRenderBundle renderBundle(String instanceId) {
        WorkflowInstance instance = requireInstance(instanceId);
        actionPolicyService.requireRecordView(instance);
        List<WorkflowNodeInstance> nodes = nodeDao.query(WorkflowTenantScope.criteria().eq("instanceId", instance.getId()),
                ALL, Sort.asc("createdAt"));
        List<WorkflowRouteInstance> routes = routeDao.query(WorkflowTenantScope.criteria().eq("instanceId", instance.getId()),
                ALL, Sort.asc("createdAt"));
        return new WorkflowRuntimeRenderBundle("RUNTIME", instance, nodes, routes);
    }

    public List<WorkflowManualBranchCandidateView> manualBranchCandidates(String instanceId) {
        WorkflowInstance instance = requireInstance(instanceId);
        actionPolicyService.requireRecordView(instance);
        var nodes = safeNodes(instance.getId());
        var routes = runtimeRoutes(instance.getId());
        var businessFacts = runtimeBusinessFacts(instance, routes);
        var frontier = runtimeFrontier(nodes, routes, businessFacts);
        return manualBranchViews(instance, nodes, routes, businessFacts, frontier);
    }

    public List<WorkflowManualBranchCandidateView> manualBranchCandidates(String instanceId, String taskId,
            List<WorkflowManualRouteSelection> selections, String operatorId) {
        WorkflowInstance instance = requireInstance(instanceId);
        actionPolicyService.requireRecordView(instance);
        String operator = requireOperator(operatorId);
        WorkflowTask task = WorkflowTenantScope.visible(taskDao.findById(requireText(taskId, "workflow task id must not be blank")));
        if (task == null || !instance.getId().equals(task.getInstanceId()))
            throw new PlatformException("workflow task does not belong to instance: " + taskId);
        if (task.getTaskStatus() != WorkflowTaskStatus.TODO || task.getTaskKind() == null || !Set.of(WorkflowTaskKind.APPROVAL,
                WorkflowTaskKind.BUSINESS, WorkflowTaskKind.RESUBMIT).contains(task.getTaskKind()))
            throw new PlatformException("workflow task is not pending continuation: " + taskId);
        String action = task.getTaskKind() == WorkflowTaskKind.APPROVAL ? "approve"
                : task.getTaskKind() == WorkflowTaskKind.BUSINESS ? "complete" : "resubmit";
        actionPolicyService.requireRuntimeAction(instance, action);
        actionPolicyService.requireTaskOperator(task, action, operator);
        if (task.getTaskKind() == WorkflowTaskKind.RESUBMIT) return List.of();
        var nodes = safeNodes(instance.getId());
        var routes = runtimeRoutes(instance.getId());
        var source = nodes.stream().filter(node -> task.getNodeInstanceId().equals(node.getId())).findFirst()
                .orElseThrow(() -> new PlatformException("workflow task node not found: " + taskId));
        if (instance.getInstanceStatus() != WorkflowInstanceStatus.RUNNING || source.getNodeStatus() != WorkflowNodeStatus.ACTIVE)
            throw new PlatformException("workflow task node is not active: " + taskId);
        if (!finishesNode(task, source)) return List.of();
        var graph = WorkflowManualBranchFrontier.frozenGraph(nodes, routes);
        var selected = new WorkflowManualRouteSelectionPolicy().selectedRoutesForPlanning(graph, selections, null);
        var businessFacts = runtimeBusinessFacts(instance, routes);
        var frontier = WorkflowManualBranchFrontier.plan(graph, nodes, routes,
                continuationTargets(source, routes), selected, businessFacts);
        return manualBranchViews(instance, nodes, routes, businessFacts, frontier);
    }

    private boolean finishesNode(WorkflowTask task, WorkflowNodeInstance node) {
        var siblings = taskDao.query(WorkflowTenantScope.criteria().eq("instanceId", task.getInstanceId())
                .eq("nodeInstanceId", node.getId()), ALL, Sort.asc("createdAt"));
        if (task.getTaskKind() == WorkflowTaskKind.BUSINESS)
            return siblings.stream().noneMatch(other -> !task.getId().equals(other.getId()) && other.getTaskStatus() == WorkflowTaskStatus.TODO);
        var completed = new WorkflowTask();
        org.springframework.beans.BeanUtils.copyProperties(task, completed);
        completed.setTaskStatus(WorkflowTaskStatus.DONE);
        var effective = new java.util.ArrayList<>(siblings.stream().filter(other -> !task.getId().equals(other.getId())).toList());
        effective.add(completed);
        return new WorkflowApprovalTaskPolicyService().isNodePassed(node.getApprovalMode(), node.getApprovalRatio(), effective);
    }

    private List<WorkflowRouteInstance> runtimeRoutes(String instanceId) {
        return routeDao.query(WorkflowTenantScope.criteria().eq("instanceId", instanceId), ALL, Sort.asc("createdAt"));
    }

    private Map<String, Object> runtimeBusinessFacts(WorkflowInstance instance, List<WorkflowRouteInstance> routes) {
        return conditions.businessFacts(routes.stream().filter(route -> route.getRouteStatus() == WorkflowRouteStatus.CANDIDATE)
                .filter(route -> !Boolean.TRUE.equals(route.getDefaultRoute()))
                .map(WorkflowRouteInstance::getConditionExpression).toList(), instance.getModuleAlias(), instance.getRecordId());
    }

    private WorkflowManualBranchFrontier.Decision runtimeFrontier(List<WorkflowNodeInstance> nodes,
            List<WorkflowRouteInstance> routes, Map<String, Object> businessFacts) {
        var graph = WorkflowManualBranchFrontier.frozenGraph(nodes, routes);
        Set<String> reached = new LinkedHashSet<>(), pending = new LinkedHashSet<>();
        for (var node : nodes) {
            if (node.getNodeStatus() != WorkflowNodeStatus.ACTIVE) continue;
            boolean manual = node.getNodeType() == WorkflowNodeType.BRANCH && node.getRouteMode() == WorkflowRouteMode.MANUAL
                    && routes.stream().anyMatch(route -> node.getNodeKey().equals(route.getSourceNodeKey())
                    && route.getRouteStatus() == WorkflowRouteStatus.CANDIDATE);
            boolean blocking = node.getNodeType() == WorkflowNodeType.TASK || node.getNodeType() == WorkflowNodeType.APPROVAL
                    && node.getApprovalMode() != WorkflowApprovalMode.NOTICE;
            if (!manual && !blocking) continue;
            var targets = manual ? List.of(WorkflowActivationTarget.of(node.getNodeKey())) : continuationTargets(node, routes);
            if (targets.isEmpty()) continue;
            WorkflowManualBranchFrontier.Decision decision;
            try {
                decision = WorkflowManualBranchFrontier.plan(graph, nodes, routes, targets, Map.of(), businessFacts);
            } catch (net.ximatai.muyun.spring.common.formula.FormulaEvaluationException | PlatformException invalidCondition) {
                // A read projection still exposes decided history when an old pending topology cannot be evaluated.
                continue;
            }
            reached.addAll(decision.reachedManualBranchNodeKeys());
            pending.addAll(decision.pendingManualBranchNodeKeys());
        }
        return new WorkflowManualBranchFrontier.Decision(reached, pending);
    }

    private List<WorkflowActivationTarget> continuationTargets(WorkflowNodeInstance node, List<WorkflowRouteInstance> routes) {
        var outgoing = routes.stream().filter(route -> node.getNodeKey().equals(route.getSourceNodeKey()))
                .filter(route -> route.getRouteStatus() == WorkflowRouteStatus.CANDIDATE).toList();
        var defaults = outgoing.stream().filter(route -> Boolean.TRUE.equals(route.getDefaultRoute())).toList();
        return (defaults.isEmpty() ? outgoing : defaults).stream()
                .map(route -> new WorkflowActivationTarget(route.getTargetNodeKey(), route.getId())).toList();
    }

    private List<WorkflowManualBranchCandidateView> manualBranchViews(WorkflowInstance instance,
            List<WorkflowNodeInstance> nodes, List<WorkflowRouteInstance> routes, Map<String, Object> businessFacts,
            WorkflowManualBranchFrontier.Decision frontier) {
        Map<String, WorkflowNodeInstance> nodeByKey = nodes.stream()
                .collect(Collectors.toMap(WorkflowNodeInstance::getNodeKey, Function.identity(), (left, right) -> left,
                        LinkedHashMap::new));
        Map<String, List<WorkflowRouteInstance>> routesBySourceNodeKey = routes.stream()
                .filter(route -> MANUAL_BRANCH_CANDIDATE_STATUSES.contains(route.getRouteStatus()))
                .collect(Collectors.groupingBy(WorkflowRouteInstance::getSourceNodeKey, LinkedHashMap::new,
                        Collectors.toList()));
        Map<String, String> expressions = new LinkedHashMap<>();
        routes.stream().filter(route -> frontier.reachedManualBranchNodeKeys().contains(route.getSourceNodeKey()))
                .filter(route -> route.getRouteStatus() == WorkflowRouteStatus.CANDIDATE)
                .filter(route -> !Boolean.TRUE.equals(route.getDefaultRoute()))
                .forEach(route -> expressions.put(route.getRouteKey(), route.getConditionExpression()));
        Map<String, Boolean> matched = conditions.manualMatches(expressions, businessFacts);
        Map<String, String> titles = routeTitles(instance);
        return nodes.stream().filter(node -> node.getNodeType() == WorkflowNodeType.BRANCH && node.getRouteMode() == WorkflowRouteMode.MANUAL)
                .filter(node -> frontier.reachedManualBranchNodeKeys().contains(node.getNodeKey()) || routesBySourceNodeKey
                        .getOrDefault(node.getNodeKey(), List.of()).stream().anyMatch(route -> route.getRouteStatus() != WorkflowRouteStatus.CANDIDATE))
                .sorted(nodeSort()).map(node -> {
                    var view = manualBranchCandidate(node, routesBySourceNodeKey.getOrDefault(node.getNodeKey(), List.of())
                            .stream().filter(route -> frontier.reachedManualBranchNodeKeys().contains(node.getNodeKey())
                            || route.getRouteStatus() != WorkflowRouteStatus.CANDIDATE).toList(), nodeByKey, matched, titles);
                    return new WorkflowManualBranchCandidateView(view.branchNodeKey(), view.branchTitle(), view.routeMode(),
                            view.selectorNodeKey(), view.requireManualSelectionReason(), view.candidates(),
                            frontier.pendingManualBranchNodeKeys().contains(node.getNodeKey()));
                }).toList();
    }

    public List<WorkflowManualBranchCandidatePrecheckView> manualBranchCandidatePrechecks(String instanceId,
                                                                                          String operatorId) {
        WorkflowInstance instance = requireInstance(instanceId);
        actionPolicyService.requireRecordView(instance);
        String validOperatorId = requireOperator(operatorId);
        List<WorkflowNodeInstance> nodes = nodeDao.query(WorkflowTenantScope.criteria().eq("instanceId", instance.getId()),
                ALL, Sort.asc("createdAt"));
        List<WorkflowRouteInstance> routes = routeDao.query(WorkflowTenantScope.criteria().eq("instanceId", instance.getId()),
                ALL, Sort.asc("createdAt"));
        List<WorkflowTask> tasks = taskDao.query(WorkflowTenantScope.criteria().eq("instanceId", instance.getId()),
                ALL, Sort.asc("createdAt"));
        var pending = runtimeFrontier(nodes, routes, runtimeBusinessFacts(instance, routes)).pendingManualBranchNodeKeys();
        Map<String, WorkflowNodeInstance> nodeByKey = nodes.stream()
                .collect(Collectors.toMap(WorkflowNodeInstance::getNodeKey, Function.identity(), (left, right) -> left,
                        LinkedHashMap::new));
        Map<String, List<WorkflowRouteInstance>> routesBySourceNodeKey = routes.stream()
                .filter(route -> MANUAL_BRANCH_CANDIDATE_STATUSES.contains(route.getRouteStatus()))
                .collect(Collectors.groupingBy(WorkflowRouteInstance::getSourceNodeKey, LinkedHashMap::new,
                        Collectors.toList()));
        return nodes.stream()
                .filter(node -> node.getNodeType() == WorkflowNodeType.BRANCH)
                .filter(node -> node.getRouteMode() == WorkflowRouteMode.MANUAL)
                .filter(node -> pending.contains(node.getNodeKey()) || routesBySourceNodeKey.getOrDefault(node.getNodeKey(), List.of())
                        .stream().anyMatch(route -> route.getRouteStatus() != WorkflowRouteStatus.CANDIDATE))
                .sorted(nodeSort())
                .map(node -> manualBranchCandidatePrecheck(instance, node, routesBySourceNodeKey.getOrDefault(
                        node.getNodeKey(), List.of()).stream().filter(route -> pending.contains(node.getNodeKey())
                        || route.getRouteStatus() != WorkflowRouteStatus.CANDIDATE).toList(), nodes, tasks, nodeByKey, validOperatorId))
                .toList();
    }

    public List<WorkflowTask> instanceTasks(String instanceId) {
        WorkflowInstance instance = requireInstance(instanceId);
        actionPolicyService.requireRecordView(instance);
        return taskDao.query(WorkflowTenantScope.criteria().eq("instanceId", instance.getId()), ALL, Sort.asc("createdAt"));
    }

    public List<WorkflowEvent> instanceEvents(String instanceId) {
        WorkflowInstance instance = requireInstance(instanceId);
        actionPolicyService.requireRecordView(instance);
        return eventDao.query(WorkflowTenantScope.criteria().eq("instanceId", instance.getId()), ALL,
                Sort.asc("occurredAt"), Sort.asc("createdAt"));
    }

    public List<WorkflowRuntimeAddSignExplanationView> addSignExplanations(String instanceId) {
        WorkflowInstance instance = requireInstance(instanceId);
        actionPolicyService.requireRecordView(instance);
        List<WorkflowNodeInstance> nodes = nodeDao.query(WorkflowTenantScope.criteria().eq("instanceId", instance.getId()),
                ALL, Sort.asc("createdAt"));
        List<WorkflowRouteInstance> routes = routeDao.query(WorkflowTenantScope.criteria().eq("instanceId", instance.getId()),
                ALL, Sort.asc("createdAt"));
        Map<String, WorkflowNodeInstance> nodesByKey = nodes.stream()
                .filter(node -> node.getNodeKey() != null)
                .collect(Collectors.toMap(WorkflowNodeInstance::getNodeKey, Function.identity(), (left, right) -> left,
                        LinkedHashMap::new));
        List<WorkflowRuntimeAddSignExplanationView> views = new ArrayList<>();
        nodes.stream()
                .filter(node -> Boolean.TRUE.equals(node.getAddedByAddSign()))
                .sorted(nodeSort())
                .map(node -> addSignNodeExplanation(node, nodesByKey))
                .forEach(views::add);
        routes.stream()
                .filter(route -> Boolean.TRUE.equals(route.getAddedByAddSign()))
                .sorted(routeSort())
                .map(route -> addSignRouteExplanation(route, nodesByKey))
                .forEach(views::add);
        return List.copyOf(views);
    }

    public List<WorkflowTaskAvailableAction> instanceAvailableActions(String instanceId, String operatorId) {
        WorkflowInstance instance = requireInstance(instanceId);
        actionPolicyService.requireRecordView(instance);
        String validOperatorId = requireOperator(operatorId);
        Map<String, WorkflowNodeInstance> nodes = nodeById(instance.getId());
        return taskDao.query(WorkflowTenantScope.criteria().eq("instanceId", instance.getId()), ALL, Sort.asc("createdAt"))
                .stream()
                .filter(task -> task.getTaskStatus() == WorkflowTaskStatus.TODO && assignmentPolicyService.canProcess(task, validOperatorId)
                        || task.getTaskStatus() == WorkflowTaskStatus.DONE && validOperatorId.equals(task.getActualProcessorId()))
                .flatMap(task -> availabilityService.availableActions(task.getId(), validOperatorId).stream()
                        .map(action -> enrich(action, task, nodes.get(task.getNodeInstanceId()))))
                .toList();
    }

    public Map<String, String> workbenchModules() { return recordSummaryResolver.modules(); }

    /** One filtered snapshot owns rows, exact total and available module labels. */
    public WorkflowWorkbenchPage workbenchPage(String board, String operator, PageRequest pageRequest,
                                               WorkflowWorkbenchQueryRequest request, String keyword) {
        List<WorkflowWorkbenchCard> source = switch (board.toUpperCase(java.util.Locale.ROOT)) {
            case "TODO" -> todoCards(operator, ALL, request);
            case "DONE" -> doneCards(operator, ALL, request);
            case "NOTICE" -> noticeCards(operator, ALL, request);
            case "TRACKING" -> trackingCards(operator, ALL, request);
            case "DELEGATION" -> delegationCards(operator, ALL, request);
            default -> throw new PlatformException("unsupported workflow workbench board: " + board);
        };
        Map<String, WorkflowRecordSummary> summaries = new LinkedHashMap<>();
        Map<String, String> moduleTitles = new LinkedHashMap<>(recordSummaryResolver.modules());
        var enriched = source.stream().map(card -> {
            var summary = summaries.computeIfAbsent(card.moduleAlias() + ":" + card.recordId(), key -> recordSummaryResolver.resolve(requireInstance(card.instanceId())));
            moduleTitles.putIfAbsent(card.moduleAlias(), summary == null ? card.moduleAlias() : summary.moduleTitle());
            return card.withBusiness(summary);
        }).toList();
        String search = keyword == null ? "" : keyword.strip().toLowerCase(java.util.Locale.ROOT);
        var filtered = enriched.stream().filter(card -> search.isEmpty() || (card.business() != null
                && card.business().readable() && card.business().title() != null
                && card.business().title().toLowerCase(java.util.Locale.ROOT).contains(search))).toList();
        var normalizedPage = page(pageRequest);
        return new WorkflowWorkbenchPage(PageResult.of(pageItems(filtered, normalizedPage), filtered.size(), normalizedPage), moduleTitles);
    }

    public List<WorkflowHistoryTaskView> instanceTaskViews(String instanceId) {
        var tasks = instanceTasks(instanceId);
        var titles = userTitles(tasks);
        return tasks.stream().map(task -> WorkflowHistoryTaskView.from(task, titles)).toList();
    }

    public List<WorkflowHistoryEventView> instanceEventViews(String instanceId) {
        var tasks = instanceTasks(instanceId);
        var events = instanceEvents(instanceId);
        var users = new LinkedHashSet<String>();
        for (var event : events) addUserId(users, event.getOperatorId());
        var titles = new LinkedHashMap<String, String>(userTitles(tasks));
        titles.putAll(userTitleResolver.titles(users));
        var byTask = tasks.stream().collect(Collectors.toMap(WorkflowTask::getId, Function.identity()));
        var nodes = safeNodes(instanceId);
        var nodesById = nodes.stream().collect(Collectors.toMap(WorkflowNodeInstance::getId, Function.identity()));
        var nodesByKey = nodes.stream().collect(Collectors.toMap(WorkflowNodeInstance::getNodeKey, Function.identity()));
        var routesByIdOrKey = new LinkedHashMap<String, WorkflowRouteInstance>();
        for (var route : routeDao.query(WorkflowTenantScope.criteria().eq("instanceId", instanceId), ALL)) {
            routesByIdOrKey.put(route.getId(), route);
            routesByIdOrKey.put(route.getRouteKey(), route);
        }
        return events.stream().map(event -> WorkflowHistoryEventView.from(event, byTask.get(event.getTaskId()),
                nodesById, nodesByKey, routesByIdOrKey, titles)).toList();
    }

    public List<WorkflowWorkbenchCard> todoCards(String assigneeId, PageRequest pageRequest) {
        return todoCards(assigneeId, pageRequest, WorkflowWorkbenchQueryRequest.empty());
    }

    public List<WorkflowWorkbenchCard> todoCards(String assigneeId, PageRequest pageRequest,
                                                 WorkflowWorkbenchQueryRequest request) {
        String validAssigneeId = requireText(assigneeId, "workflow assignee id must not be blank");
        List<WorkflowTask> tasks = taskDao.query(WorkflowTenantScope.criteria()
                        .eq("taskStatus", WorkflowTaskStatus.TODO)
                        .in("taskKind", List.of(WorkflowTaskKind.APPROVAL, WorkflowTaskKind.BUSINESS,
                                WorkflowTaskKind.RESUBMIT)),
                ALL, Sort.asc("dueAt"), Sort.desc("createdAt")).stream()
                .filter(task -> assignmentPolicyService.canSeeTodo(task, validAssigneeId))
                .toList();
        return pageItems(cards("TODO", tasks, request), page(pageRequest));
    }

    public List<WorkflowWorkbenchCard> doneCards(String processorId, PageRequest pageRequest) {
        return doneCards(processorId, pageRequest, WorkflowWorkbenchQueryRequest.empty());
    }

    public List<WorkflowWorkbenchCard> doneCards(String processorId, PageRequest pageRequest,
                                                 WorkflowWorkbenchQueryRequest request) {
        String validProcessorId = requireText(processorId, "workflow processor id must not be blank");
        List<WorkflowTask> tasks = taskDao.query(WorkflowTenantScope.criteria()
                        .in("taskStatus", List.of(WorkflowTaskStatus.DONE, WorkflowTaskStatus.REJECTED,
                                WorkflowTaskStatus.ROLLED_BACK, WorkflowTaskStatus.TRANSFERRED)),
                ALL, Sort.desc("completedAt"), Sort.desc("updatedAt")).stream()
                .filter(task -> validProcessorId.equals(task.getActualProcessorId())
                        || validProcessorId.equals(task.getTransferredBy()))
                .toList();
        return pageItems(cards("DONE", tasks, request), page(pageRequest));
    }

    public List<WorkflowWorkbenchCard> noticeCards(String assigneeId, PageRequest pageRequest) {
        return noticeCards(assigneeId, pageRequest, WorkflowWorkbenchQueryRequest.empty());
    }

    public List<WorkflowWorkbenchCard> noticeCards(String assigneeId, PageRequest pageRequest,
                                                   WorkflowWorkbenchQueryRequest request) {
        String validAssigneeId = requireText(assigneeId, "workflow assignee id must not be blank");
        List<WorkflowTask> tasks = taskDao.query(WorkflowTenantScope.criteria()
                        .eq("assigneeId", validAssigneeId)
                        .eq("taskKind", WorkflowTaskKind.NOTICE)
                        .in("taskStatus", List.of(WorkflowTaskStatus.TODO, WorkflowTaskStatus.NOTICED)),
                ALL, Sort.desc("createdAt"));
        return pageItems(cards("NOTICE", tasks, request), page(pageRequest));
    }

    public WorkflowWorkbenchStats workbenchStats(String boardType, String operatorId) {
        return workbenchStats(boardType, operatorId, WorkflowWorkbenchQueryRequest.empty());
    }

    public WorkflowWorkbenchStats workbenchStats(String boardType, String operatorId,
                                                 WorkflowWorkbenchQueryRequest request) {
        String normalizedBoard = requireText(boardType, "workflow workbench board type must not be blank")
                .toUpperCase();
        String validOperatorId = requireText(operatorId, "workflow operator id must not be blank");
        WorkflowWorkbenchQueryRequest filters = filtersOnly(request);
        return switch (normalizedBoard) {
            case "TRACKING" -> trackingStats(validOperatorId, filters);
            case "TODO" -> todoStats(validOperatorId, filters);
            case "DONE" -> doneStats(validOperatorId, filters);
            case "NOTICE" -> noticeStats(validOperatorId, filters);
            case "DELEGATION" -> delegationStats(validOperatorId, filters);
            default -> throw new PlatformException("unsupported workflow workbench board type: " + boardType);
        };
    }

    private WorkflowWorkbenchStats trackingStats(String starterId, WorkflowWorkbenchQueryRequest request) {
        List<WorkflowWorkbenchCard> cards = trackingCards(starterId, ALL, request);
        EnumMap<WorkflowInstanceStatus, Long> counts = new EnumMap<>(WorkflowInstanceStatus.class);
        cards.forEach(card -> counts.merge(card.instanceStatus(), 1L, Long::sum));
        return new WorkflowWorkbenchStats("TRACKING", statsWithAll(cards.size(), WorkflowInstanceStatus.values(), counts));
    }

    private WorkflowManualBranchCandidateView manualBranchCandidate(WorkflowNodeInstance node,
                                                                    List<WorkflowRouteInstance> routes,
                                                                    Map<String, WorkflowNodeInstance> nodeByKey,
                                                                    Map<String, Boolean> matched,
                                                                    Map<String, String> routeTitles) {
        var pending = routes.stream().filter(route -> route.getRouteStatus() == WorkflowRouteStatus.CANDIDATE).toList();
        boolean anyMatched = pending.stream().anyMatch(route -> Boolean.TRUE.equals(matched.get(route.getRouteKey())));
        boolean known = pending.stream().filter(route -> !Boolean.TRUE.equals(route.getDefaultRoute()))
                .allMatch(route -> matched.get(route.getRouteKey()) != null);
        List<WorkflowManualBranchCandidateView.Candidate> candidates = routes.stream()
                .sorted(routeSort())
                .map(route -> {
                    WorkflowNodeInstance target = nodeByKey.get(route.getTargetNodeKey());
                    boolean defaultRoute = Boolean.TRUE.equals(route.getDefaultRoute());
                    boolean undecided = route.getRouteStatus() == WorkflowRouteStatus.CANDIDATE;
                    Boolean conditionMatched = undecided
                            ? (defaultRoute ? Boolean.FALSE : matched.get(route.getRouteKey())) : route.getConditionMatched();
                    String targetTitle = target == null ? route.getTargetNodeKey() : nodeTitle(target);
                    return new WorkflowManualBranchCandidateView.Candidate(
                            route.getId(),
                            route.getRouteKey(),
                            route.getTargetNodeKey(),
                            target == null ? null : target.getNodeType(),
                            route.getRouteStatus(),
                            route.getDefaultRoute(), firstText(routeTitles.get(route.getRouteKey()), targetTitle),
                            targetTitle, conditionMatched,
                            undecided && known && (defaultRoute ? !anyMatched : Boolean.TRUE.equals(conditionMatched)));
                })
                .toList();
        return new WorkflowManualBranchCandidateView(
                node.getNodeKey(),
                nodeTitle(node),
                node.getRouteMode(),
                node.getSelectorNodeKey(),
                node.getRequireManualSelectionReason(),
                candidates);
    }

    private Map<String, String> routeTitles(WorkflowInstance instance) {
        if (instance.getSemanticJson() == null || instance.getSemanticJson().isBlank()) return Map.of();
        Map<String, String> titles = new LinkedHashMap<>();
        try {
            JsonNode snapshot = OBJECT_MAPPER.readTree(instance.getSemanticJson());
            if (snapshot == null) return Map.of();
            for (JsonNode link : snapshot.path("links")) {
                String key = blankToNull(link.path("routeKey").asText(null));
                String title = blankToNull(link.path("title").asText(null));
                if (key != null && title != null) titles.put(key, title);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalidSnapshot) {
            // Older or externally supplied designer snapshots may omit readable labels.
            return Map.of();
        }
        return titles;
    }

    private WorkflowManualBranchCandidatePrecheckView manualBranchCandidatePrecheck(WorkflowInstance instance,
                                                                                    WorkflowNodeInstance node,
                                                                                    List<WorkflowRouteInstance> routes,
                                                                                    List<WorkflowNodeInstance> nodes,
                                                                                    List<WorkflowTask> tasks,
                                                                                    Map<String, WorkflowNodeInstance> nodeByKey,
                                                                                    String operatorId) {
        WorkflowManualBranchSelectorResolver.SelectorResolution selectorResolution =
                manualBranchSelectorResolver.resolve(instance, nodes, tasks, node.getSelectorNodeKey(),
                        node.getNodeKey(), operatorId);
        boolean hasSelectableRoute = routes.stream()
                .anyMatch(route -> route.getRouteStatus() == WorkflowRouteStatus.CANDIDATE);
        boolean branchSelectable = selectorResolution.selectable() && hasSelectableRoute;
        String branchUnselectableReason = branchSelectable
                ? null
                : firstText(selectorResolution.unselectableReason(), ROUTE_ALREADY_DECIDED);
        List<WorkflowManualBranchCandidatePrecheckView.Candidate> candidates = routes.stream()
                .sorted(routeSort())
                .map(route -> manualBranchRouteCandidate(route, nodeByKey, selectorResolution))
                .toList();
        return new WorkflowManualBranchCandidatePrecheckView(
                node.getNodeKey(),
                node.getRouteMode(),
                selectorResolution.selectorNodeKey(),
                node.getRequireManualSelectionReason(),
                selectorResolution.resolvedUserId(),
                operatorId,
                branchSelectable,
                branchUnselectableReason,
                candidates);
    }

    private WorkflowManualBranchCandidatePrecheckView.Candidate manualBranchRouteCandidate(
            WorkflowRouteInstance route,
            Map<String, WorkflowNodeInstance> nodeByKey,
            WorkflowManualBranchSelectorResolver.SelectorResolution selectorResolution) {
        WorkflowNodeInstance target = nodeByKey.get(route.getTargetNodeKey());
        boolean routeSelectable = selectorResolution.selectable()
                && route.getRouteStatus() == WorkflowRouteStatus.CANDIDATE;
        String routeUnselectableReason = routeSelectable
                ? null
                : routeUnselectableReason(route, selectorResolution);
        return new WorkflowManualBranchCandidatePrecheckView.Candidate(
                route.getId(),
                route.getRouteKey(),
                route.getTargetNodeKey(),
                target == null ? null : target.getNodeType(),
                route.getRouteStatus(),
                route.getDefaultRoute(),
                routeSelectable,
                routeUnselectableReason);
    }

    private String routeUnselectableReason(WorkflowRouteInstance route,
                                           WorkflowManualBranchSelectorResolver.SelectorResolution selectorResolution) {
        if (route.getRouteStatus() != WorkflowRouteStatus.CANDIDATE) {
            return ROUTE_ALREADY_DECIDED;
        }
        return selectorResolution.unselectableReason();
    }

    private WorkflowRuntimeAddSignExplanationView addSignNodeExplanation(
            WorkflowNodeInstance node,
            Map<String, WorkflowNodeInstance> nodesByKey) {
        return new WorkflowRuntimeAddSignExplanationView(
                WorkflowRuntimeAddSignExplanationView.ORIGIN_TYPE_ADD_SIGN,
                WorkflowRuntimeAddSignExplanationView.DIMENSION_NODE,
                Boolean.FALSE,
                node.getId(),
                node.getNodeKey(),
                node.getNodeType(),
                node.getNodeStatus(),
                null,
                null,
                null,
                null,
                null,
                node.getAddSignSourceNodeKey(),
                sourceNodeName(node.getAddSignSourceNodeKey(), nodesByKey),
                node.getAddSignOperatorId(),
                node.getAddSignAt());
    }

    private WorkflowRuntimeAddSignExplanationView addSignRouteExplanation(
            WorkflowRouteInstance route,
            Map<String, WorkflowNodeInstance> nodesByKey) {
        return new WorkflowRuntimeAddSignExplanationView(
                WorkflowRuntimeAddSignExplanationView.ORIGIN_TYPE_ADD_SIGN,
                WorkflowRuntimeAddSignExplanationView.DIMENSION_ROUTE,
                Boolean.TRUE,
                null,
                null,
                null,
                null,
                route.getId(),
                route.getRouteKey(),
                route.getSourceNodeKey(),
                route.getTargetNodeKey(),
                route.getRouteStatus(),
                route.getAddSignSourceNodeKey(),
                sourceNodeName(route.getAddSignSourceNodeKey(), nodesByKey),
                route.getAddSignOperatorId(),
                route.getAddSignAt());
    }

    private String sourceNodeName(String sourceNodeKey, Map<String, WorkflowNodeInstance> nodesByKey) {
        String validSourceNodeKey = blankToNull(sourceNodeKey);
        if (validSourceNodeKey == null) {
            return null;
        }
        WorkflowNodeInstance sourceNode = nodesByKey.get(validSourceNodeKey);
        return firstText(snapshotText(sourceNode, "nodeName"),
                firstText(snapshotText(sourceNode, "name"),
                        firstText(snapshotText(sourceNode, "title"), validSourceNodeKey)));
    }

    private String snapshotText(WorkflowNodeInstance node, String fieldName) {
        if (node == null || node.getNodeSnapshotText() == null || node.getNodeSnapshotText().isBlank()) {
            return null;
        }
        try {
            JsonNode value = OBJECT_MAPPER.readTree(node.getNodeSnapshotText()).path(fieldName);
            if (value.isMissingNode() || value.isNull()) {
                return null;
            }
            return blankToNull(value.asText(null));
        } catch (Exception ignored) {
            return null;
        }
    }

    private Comparator<WorkflowNodeInstance> nodeSort() {
        return Comparator
                .comparing(WorkflowNodeInstance::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(WorkflowNodeInstance::getNodeKey, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private Comparator<WorkflowRouteInstance> routeSort() {
        return Comparator
                .comparing(WorkflowRouteInstance::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(WorkflowRouteInstance::getRouteKey, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private WorkflowWorkbenchStats todoStats(String assigneeId, WorkflowWorkbenchQueryRequest request) {
        List<WorkflowWorkbenchCard> cards = todoCards(assigneeId, ALL, request);
        EnumMap<WorkflowOvertimeStatus, Long> counts = new EnumMap<>(WorkflowOvertimeStatus.class);
        cards.forEach(card -> counts.merge(overtimeStatus(card), 1L, Long::sum));
        return new WorkflowWorkbenchStats("TODO", statsWithAll(cards.size(), WorkflowOvertimeStatus.values(), counts));
    }

    private WorkflowWorkbenchStats doneStats(String processorId, WorkflowWorkbenchQueryRequest request) {
        List<WorkflowWorkbenchCard> cards = doneCards(processorId, ALL, request);
        Map<String, Long> counts = new LinkedHashMap<>();
        cards.forEach(card -> counts.merge(doneStatCode(card), 1L, Long::sum));
        return new WorkflowWorkbenchStats("DONE", List.of(
                stat("ALL", "全部", cards.size()),
                stat("DONE", WorkflowTaskStatus.DONE.getTitle(), counts.getOrDefault("DONE", 0L)),
                stat("REJECTED", WorkflowTaskStatus.REJECTED.getTitle(), counts.getOrDefault("REJECTED", 0L)),
                stat("ROLLED_BACK", WorkflowTaskStatus.ROLLED_BACK.getTitle(), counts.getOrDefault("ROLLED_BACK", 0L)),
                stat("TRANSFERRED", WorkflowTaskStatus.TRANSFERRED.getTitle(), counts.getOrDefault("TRANSFERRED", 0L))
        ));
    }

    private WorkflowWorkbenchStats noticeStats(String assigneeId, WorkflowWorkbenchQueryRequest request) {
        List<WorkflowWorkbenchCard> cards = noticeCards(assigneeId, ALL, request);
        EnumMap<WorkflowNoticeReadStatus, Long> counts = new EnumMap<>(WorkflowNoticeReadStatus.class);
        cards.forEach(card -> counts.merge(card.readStatus(), 1L, Long::sum));
        return new WorkflowWorkbenchStats("NOTICE", List.of(
                stat(WorkflowNoticeReadStatus.ALL, cards.size()),
                stat(WorkflowNoticeReadStatus.UNREAD, counts.getOrDefault(WorkflowNoticeReadStatus.UNREAD, 0L)),
                stat(WorkflowNoticeReadStatus.READ, counts.getOrDefault(WorkflowNoticeReadStatus.READ, 0L))
        ));
    }

    private WorkflowWorkbenchStats delegationStats(String principalId, WorkflowWorkbenchQueryRequest request) {
        List<WorkflowWorkbenchCard> cards = delegationCards(principalId, ALL, request);
        EnumMap<WorkflowOvertimeStatus, Long> counts = new EnumMap<>(WorkflowOvertimeStatus.class);
        cards.forEach(card -> counts.merge(overtimeStatus(card), 1L, Long::sum));
        return new WorkflowWorkbenchStats("DELEGATION", statsWithAll(cards.size(), WorkflowOvertimeStatus.values(), counts));
    }

    public List<WorkflowWorkbenchCard> trackingCards(String starterId, PageRequest pageRequest) {
        return trackingCards(starterId, pageRequest, WorkflowWorkbenchQueryRequest.empty());
    }

    public List<WorkflowWorkbenchCard> trackingCards(String starterId, PageRequest pageRequest,
                                                     WorkflowWorkbenchQueryRequest request) {
        String validStarterId = requireText(starterId, "workflow starter id must not be blank");
        List<WorkflowInstance> instances = instanceDao.query(WorkflowTenantScope.criteria().eq("startedBy", validStarterId),
                ALL, Sort.desc("startedAt"), Sort.desc("updatedAt"));
        Map<String, String> userTitles = userTitles(List.of(), instances);
        List<WorkflowWorkbenchCard> cards = instances.stream()
                .map(instance -> card("TRACKING", instance, null, currentAddSignNode(instance),
                        currentAssignees(instance.getId()), userTitles))
                .filter(card -> matches(card, request))
                .sorted(sorter("TRACKING", request))
                .toList();
        return pageItems(cards, page(pageRequest));
    }

    public List<WorkflowWorkbenchCard> delegationCards(String principalId, PageRequest pageRequest) {
        return delegationCards(principalId, pageRequest, WorkflowWorkbenchQueryRequest.empty());
    }

    public List<WorkflowWorkbenchCard> delegationCards(String principalId, PageRequest pageRequest,
                                                       WorkflowWorkbenchQueryRequest request) {
        String validPrincipalId = requireText(principalId, "workflow delegation principal id must not be blank");
        List<WorkflowTask> tasks = taskDao.query(WorkflowTenantScope.criteria()
                        .eq("assignmentKind", WorkflowAssignmentKind.DELEGATED)
                        .eq("delegatedFromUserId", validPrincipalId)
                        .eq("taskStatus", WorkflowTaskStatus.TODO),
                ALL, Sort.asc("dueAt"), Sort.desc("createdAt"));
        Map<String, List<WorkflowTask>> tasksByInstance = new LinkedHashMap<>();
        for (WorkflowTask task : tasks) {
            tasksByInstance.computeIfAbsent(task.getInstanceId(), ignored -> new java.util.ArrayList<>()).add(task);
        }
        List<WorkflowWorkbenchCard> cards = tasksByInstance.values().stream()
                .map(this::delegationCard)
                .filter(card -> matches(card, request))
                .sorted(sorter("DELEGATION", request))
                .toList();
        return pageItems(cards, page(pageRequest));
    }

    private List<WorkflowWorkbenchCard> cards(String boardType, List<WorkflowTask> tasks) {
        return cards(boardType, tasks, WorkflowWorkbenchQueryRequest.empty());
    }

    private List<WorkflowWorkbenchCard> cards(String boardType, List<WorkflowTask> tasks,
                                              WorkflowWorkbenchQueryRequest request) {
        Map<String, WorkflowInstance> instances = new LinkedHashMap<>();
        Map<String, WorkflowNodeInstance> nodes = new LinkedHashMap<>();
        for (WorkflowTask task : tasks) {
            instances.computeIfAbsent(task.getInstanceId(), instanceDao::findById);
            if (task.getNodeInstanceId() != null) {
                nodes.computeIfAbsent(task.getNodeInstanceId(), nodeDao::findById);
            }
        }
        Map<String, String> userTitles = userTitles(tasks, instances.values());
        return tasks.stream()
                .map(task -> card(boardType, instances.get(task.getInstanceId()), task,
                        nodes.get(task.getNodeInstanceId()), assigneeIds(task), userTitles))
                .filter(card -> matches(card, request))
                .sorted(sorter(boardType, request))
                .toList();
    }

    private List<String> assigneeIds(WorkflowTask task) {
        if (task == null || task.getAssigneeId() == null || task.getAssigneeId().isBlank()) {
            return List.of();
        }
        if (task.getAssignmentKind() == WorkflowAssignmentKind.DELEGATED
                && Boolean.TRUE.equals(task.getPrincipalCanProcess())
                && task.getDelegatedFromUserId() != null
                && !task.getDelegatedFromUserId().isBlank()
                && task.getTransferredFromUserId() == null) {
            return List.of(task.getAssigneeId(), task.getDelegatedFromUserId());
        }
        return List.of(task.getAssigneeId());
    }

    private WorkflowWorkbenchCard card(String boardType,
                                       WorkflowInstance instance,
                                       WorkflowTask task,
                                       WorkflowNodeInstance node,
                                       List<String> currentAssigneeIds) {
        return card(boardType, instance, task, node, currentAssigneeIds, (Map<String, String>) null);
    }

    private WorkflowWorkbenchCard delegationCard(List<WorkflowTask> tasks) {
        WorkflowTask representative = tasks.stream()
                .sorted(Comparator.comparing(WorkflowTask::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .findFirst()
                .orElseThrow(() -> new PlatformException("workflow delegation task must not be empty"));
        WorkflowInstance instance = instanceDao.findById(representative.getInstanceId());
        WorkflowNodeInstance node = representative.getNodeInstanceId() == null
                ? null
                : nodeDao.findById(representative.getNodeInstanceId());
        List<String> currentAssigneeIds = tasks.stream()
                .flatMap(task -> assigneeIds(task).stream())
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        return card("DELEGATION", instance, representative, node, currentAssigneeIds,
                userTitles(tasks, List.of(instance)), tasks.size());
    }

    private WorkflowWorkbenchCard card(String boardType,
                                       WorkflowInstance instance,
                                       WorkflowTask task,
                                       WorkflowNodeInstance node,
                                       List<String> currentAssigneeIds,
                                       Map<String, String> userTitles) {
        return card(boardType, instance, task, node, currentAssigneeIds, userTitles, null);
    }

    private WorkflowWorkbenchCard card(String boardType,
                                       WorkflowInstance instance,
                                       WorkflowTask task,
                                       WorkflowNodeInstance node,
                                       List<String> currentAssigneeIds,
                                       Integer delegationTaskCount) {
        return card(boardType, instance, task, node, currentAssigneeIds, userTitles(task), delegationTaskCount);
    }

    private WorkflowWorkbenchCard card(String boardType,
                                       WorkflowInstance instance,
                                       WorkflowTask task,
                                       WorkflowNodeInstance node,
                                       List<String> currentAssigneeIds,
                                       Map<String, String> userTitles,
                                       Integer delegationTaskCount) {
        if (instance == null) {
            throw new PlatformException("workflow instance not found: " + (task == null ? null : task.getInstanceId()));
        }
        return new WorkflowWorkbenchCard(boardType, instance.getId(), instance.getModuleAlias(), instance.getRecordId(),
                instance.getDefinitionId(), instance.getWorkflowVersionId(),
                instance.getInstanceStatus(), instance.getApprovalStatus(), task == null ? null : task.getId(),
                task == null ? null : task.getTaskKind(), task == null ? null : task.getTaskStatus(),
                node == null ? null : node.getNodeKey(), nodeTitle(node), instance.getCurrentNodeKeys(),
                currentAssigneeIds, titles(currentAssigneeIds, userTitles),
                instance.getStartedAt(), task == null ? instance.getStartedAt() : task.getCreatedAt(),
                task == null ? instance.getCompletedAt() : task.getCompletedAt(),
                task == null ? instance.getLastActionCode() : actionCode(task),
                node == null ? null : node.getOvertimeStatus(), task == null ? null : task.getDueAt(),
                instance.getLastOperatedAt() == null ? instance.getStartedAt() : instance.getLastOperatedAt(),
                task == null ? null : task.getAssignmentKind(), task == null ? null : task.getOriginalAssigneeId(),
                title(task == null ? null : task.getOriginalAssigneeId(), userTitles),
                task == null ? null : task.getDelegatedFromUserId(),
                title(task == null ? null : task.getDelegatedFromUserId(), userTitles),
                task == null ? null : firstText(task.getDelegatedToUserId(), task.getAssigneeId()),
                title(task == null ? null : firstText(task.getDelegatedToUserId(), task.getAssigneeId()),
                        userTitles),
                task == null ? null : task.getPrincipalCanProcess(),
                noticeReadStatus(task), noticeSourceType(task), delegationTaskCount,
                Boolean.TRUE.equals(node == null ? null : node.getAddedByAddSign()),
                addSignSourceNodeKey(node), addSignOperatorId(node), title(addSignOperatorId(node), userTitles),
                addSignAt(node), instance.getStartedBy(), title(instance.getStartedBy(), userTitles));
    }

    private String addSignSourceNodeKey(WorkflowNodeInstance node) {
        return node != null && Boolean.TRUE.equals(node.getAddedByAddSign())
                ? blankToNull(node.getAddSignSourceNodeKey())
                : null;
    }

    private String addSignOperatorId(WorkflowNodeInstance node) {
        return node != null && Boolean.TRUE.equals(node.getAddedByAddSign())
                ? blankToNull(node.getAddSignOperatorId())
                : null;
    }

    private Map<String, String> userTitles(WorkflowTask task) {
        return userTitles(task == null ? List.of() : List.of(task));
    }

    private Map<String, String> userTitles(List<WorkflowTask> tasks) {
        return userTitles(tasks, List.of());
    }

    private Map<String, String> userTitles(List<WorkflowTask> tasks, Iterable<WorkflowInstance> instances) {
        LinkedHashSet<String> userIds = new LinkedHashSet<>();
        for (WorkflowTask task : tasks == null ? List.<WorkflowTask>of() : tasks) {
            addUserId(userIds, task.getAssigneeId());
            addUserId(userIds, task.getOriginalAssigneeId());
            addUserId(userIds, task.getActualProcessorId());
            addUserId(userIds, task.getDelegatedFromUserId());
            addUserId(userIds, task.getDelegatedToUserId());
            addUserId(userIds, task.getTransferredFromUserId());
            addUserId(userIds, task.getTransferredBy());
        }
        if (instances != null) {
            for (WorkflowInstance instance : instances) {
                addUserId(userIds, instance == null ? null : instance.getStartedBy());
            }
        }
        Map<String, String> titles = userTitleResolver.titles(userIds);
        return titles == null ? Map.of() : titles;
    }

    private void addUserId(Set<String> userIds, String userId) {
        if (userId != null && !userId.isBlank()) {
            userIds.add(userId);
        }
    }

    private List<String> titles(List<String> userIds, Map<String, String> userTitles) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        Map<String, String> titles = userTitles == null ? Map.of() : userTitles;
        return userIds.stream()
                .map(titles::get)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private String title(String userId, Map<String, String> userTitles) {
        return userId == null || userTitles == null ? null : userTitles.get(userId);
    }

    private String nodeTitle(WorkflowNodeInstance node) {
        return node == null ? null : firstText(node.getNodeTitle(), node.getNodeKey());
    }

    private Instant addSignAt(WorkflowNodeInstance node) {
        return node != null && Boolean.TRUE.equals(node.getAddedByAddSign()) ? node.getAddSignAt() : null;
    }

    private String noticeSourceType(WorkflowTask task) {
        if (task == null || task.getTaskKind() != WorkflowTaskKind.NOTICE) {
            return null;
        }
        if (isDelegationCompletionNotice(task)) {
            return "DELEGATION_COMPLETED";
        }
        return null;
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

    private WorkflowTaskAvailableAction enrich(WorkflowTaskAvailableAction action, WorkflowTask task,
                                               WorkflowNodeInstance node) {
        WorkflowTaskAvailableAction enriched = action.withTask(task, node);
        if ("reject".equals(action.actionCode())) {
            List<String> modes = action.rejectReturnToMeSupported()
                    ? List.of(WorkflowRejectResubmitMode.RESTART.getCode(),
                    WorkflowRejectResubmitMode.RETURN_TO_ME.getCode())
                    : List.of(WorkflowRejectResubmitMode.RESTART.getCode());
            enriched = enriched.withRejectResubmitModes(modes, WorkflowRejectResubmitMode.RESTART.getCode());
        }
        return enriched;
    }

    private Map<String, WorkflowNodeInstance> nodeById(String instanceId) {
        Map<String, WorkflowNodeInstance> values = new LinkedHashMap<>();
        nodeDao.query(WorkflowTenantScope.criteria().eq("instanceId", instanceId), ALL, Sort.asc("createdAt"))
                .forEach(node -> values.put(node.getId(), node));
        return values;
    }

    private List<String> currentAssignees(String instanceId) {
        return taskDao.query(WorkflowTenantScope.criteria()
                        .eq("instanceId", instanceId)
                        .eq("taskStatus", WorkflowTaskStatus.TODO),
                ALL, Sort.asc("createdAt"))
                .stream()
                .flatMap(task -> assigneeIds(task).stream())
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private WorkflowNodeInstance currentAddSignNode(WorkflowInstance instance) {
        if (instance == null || instance.getCurrentNodeKeys() == null || instance.getCurrentNodeKeys().isBlank()) {
            return null;
        }
        Map<String, WorkflowNodeInstance> addSignNodesByKey = safeNodes(instance.getId()).stream()
                .filter(node -> Boolean.TRUE.equals(node.getAddedByAddSign()))
                .filter(node -> node.getNodeKey() != null)
                .collect(Collectors.toMap(WorkflowNodeInstance::getNodeKey, Function.identity(), (left, right) -> left,
                        LinkedHashMap::new));
        if (addSignNodesByKey.isEmpty()) {
            return null;
        }
        for (String nodeKey : instance.getCurrentNodeKeys().split("[,;\\s]+")) {
            WorkflowNodeInstance node = addSignNodesByKey.get(nodeKey);
            if (node != null) {
                return node;
            }
        }
        return null;
    }

    private List<WorkflowNodeInstance> safeNodes(String instanceId) {
        List<WorkflowNodeInstance> nodes = nodeDao.query(WorkflowTenantScope.criteria().eq("instanceId", instanceId),
                ALL, Sort.asc("createdAt"));
        return nodes == null ? List.of() : nodes;
    }

    private WorkflowInstance requireInstance(String instanceId) {
        String validInstanceId = requireText(instanceId, "workflow instance id must not be blank");
        WorkflowInstance instance = WorkflowTenantScope.visible(instanceDao.findById(validInstanceId));
        if (instance == null) {
            throw new PlatformException("workflow instance not found: " + validInstanceId);
        }
        return instance;
    }

    private String requireOperator(String operatorId) {
        if (operatorId != null && !operatorId.isBlank()) {
            return operatorId;
        }
        return CurrentUserContext.currentUser()
                .map(user -> user.userId())
                .filter(userId -> !userId.isBlank())
                .orElseThrow(() -> new PlatformException("workflow operator id must not be blank"));
    }

    private PageRequest page(PageRequest pageRequest) {
        return pageRequest == null ? PageRequest.of(1, 20) : pageRequest;
    }

    private boolean matches(WorkflowWorkbenchCard card, WorkflowWorkbenchQueryRequest request) {
        WorkflowWorkbenchQueryRequest normalized = request == null ? WorkflowWorkbenchQueryRequest.empty() : request;
        if (!sameText(normalized.moduleAlias(), card.moduleAlias())) {
            return false;
        }
        if (!sameText(normalized.recordId(), card.recordId())) {
            return false;
        }
        if (!sameText(normalized.definitionId(), card.definitionId())) {
            return false;
        }
        if (!sameText(normalized.effectiveWorkflowVersionId(), card.workflowVersionId())) {
            return false;
        }
        if (!same(normalized.instanceStatus(), card.instanceStatus())
                || !same(normalized.taskKind(), card.taskKind())
                || !same(normalized.taskStatus(), card.taskStatus())
                || !same(normalized.assignmentKind(), card.assignmentKind())
                || !same(normalized.overtimeStatus(), card.overtimeStatus())) {
            return false;
        }
        if (!matchesReadStatus(normalized.readStatus(), card.readStatus())) {
            return false;
        }
        if (!same(normalized.addedByAddSign(), card.addedByAddSign())) {
            return false;
        }
        if (!sameText(normalized.addSignSourceNodeKey(), card.addSignSourceNodeKey())) {
            return false;
        }
        if (!sameText(normalized.submitterUserId(), card.submitterUserId())) {
            return false;
        }
        if (!matchesNodeKey(normalized.nodeKey(), card)) {
            return false;
        }
        return inRange(card.startedAt(), normalized.startedFrom(), normalized.startedTo())
                && inRange(card.receivedAt(), normalized.receivedFrom(), normalized.receivedTo())
                && inRange(card.completedAt(), normalized.completedFrom(), normalized.completedTo())
                && inRange(card.lastOperatedAt(), normalized.lastOperatedFrom(), normalized.lastOperatedTo())
                && inRange(card.dueAt(), normalized.dueFrom(), normalized.dueTo());
    }

    private WorkflowWorkbenchQueryRequest filtersOnly(WorkflowWorkbenchQueryRequest request) {
        WorkflowWorkbenchQueryRequest normalized = request == null ? WorkflowWorkbenchQueryRequest.empty() : request;
        return new WorkflowWorkbenchQueryRequest(normalized.moduleAlias(), normalized.recordId(),
                normalized.definitionId(), normalized.workflowVersionId(), normalized.definitionVersionId(),
                normalized.instanceStatus(), normalized.nodeKey(), normalized.taskKind(), normalized.taskStatus(),
                normalized.assignmentKind(), normalized.overtimeStatus(), normalized.readStatus(),
                normalized.startedFrom(), normalized.startedTo(), normalized.receivedFrom(), normalized.receivedTo(),
                normalized.completedFrom(), normalized.completedTo(), normalized.lastOperatedFrom(),
                normalized.lastOperatedTo(), normalized.dueFrom(), normalized.dueTo(), normalized.addedByAddSign(),
                normalized.addSignSourceNodeKey(), normalized.submitterUserId(), List.of());
    }

    private Comparator<WorkflowWorkbenchCard> sorter(String boardType, WorkflowWorkbenchQueryRequest request) {
        WorkflowWorkbenchQueryRequest normalized = request == null ? WorkflowWorkbenchQueryRequest.empty() : request;
        List<WorkflowWorkbenchSort> sorts = normalized.sorts().isEmpty() ? defaultSorts(boardType) : normalized.sorts();
        Comparator<WorkflowWorkbenchCard> comparator = null;
        for (WorkflowWorkbenchSort sort : sorts) {
            String field = requireText(sort == null ? null : sort.field(),
                    "workflow workbench sort field must not be blank");
            validateSortField(field);
            WorkflowSortDirection direction = sort.direction() == null ? WorkflowSortDirection.DESC : sort.direction();
            Comparator<WorkflowWorkbenchCard> fieldComparator = (left, right) ->
                    compareSortValue(sortValue(left, field), sortValue(right, field), direction);
            comparator = comparator == null ? fieldComparator : comparator.thenComparing(fieldComparator);
        }
        Comparator<WorkflowWorkbenchCard> stable = Comparator.comparing(WorkflowWorkbenchCard::instanceId)
                .thenComparing(WorkflowWorkbenchCard::taskId, Comparator.nullsFirst(Comparator.naturalOrder()));
        return comparator == null ? stable : comparator.thenComparing(stable);
    }

    private void validateSortField(String field) {
        switch (field) {
            case "moduleAlias", "recordId", "definitionId", "workflowVersionId", "definitionVersionId",
                    "instanceStatus", "approvalStatus", "nodeKey", "taskKind", "taskStatus", "actionCode",
                    "assignmentKind", "overtimeStatus", "readStatus", "startedAt", "receivedAt", "completedAt",
                    "lastOperatedAt", "dueAt", "addSignAt", "addedByAddSign", "addSignSourceNodeKey",
                    "addSignOperatorId", "originalAssigneeId", "delegatedFromUserId", "delegatedToUserId",
                    "principalCanProcess",
                    "delegationTaskCount", "submitterUserId" -> {
            }
            default -> throw new PlatformException("unsupported workflow workbench sort field: " + field);
        }
    }

    private List<WorkflowWorkbenchSort> defaultSorts(String boardType) {
        return switch (boardType) {
            case "TODO" -> List.of(new WorkflowWorkbenchSort("dueAt", WorkflowSortDirection.ASC),
                    new WorkflowWorkbenchSort("receivedAt", WorkflowSortDirection.DESC));
            case "DONE" -> List.of(new WorkflowWorkbenchSort("completedAt", WorkflowSortDirection.DESC),
                    new WorkflowWorkbenchSort("lastOperatedAt", WorkflowSortDirection.DESC));
            case "NOTICE" -> List.of(new WorkflowWorkbenchSort("receivedAt", WorkflowSortDirection.DESC));
            case "TRACKING" -> List.of(new WorkflowWorkbenchSort("startedAt", WorkflowSortDirection.DESC),
                    new WorkflowWorkbenchSort("lastOperatedAt", WorkflowSortDirection.DESC));
            case "DELEGATION" -> List.of(new WorkflowWorkbenchSort("dueAt", WorkflowSortDirection.ASC),
                    new WorkflowWorkbenchSort("receivedAt", WorkflowSortDirection.DESC));
            default -> List.of(new WorkflowWorkbenchSort("lastOperatedAt", WorkflowSortDirection.DESC));
        };
    }

    private Comparable<?> sortValue(WorkflowWorkbenchCard card, String field) {
        return switch (field) {
            case "moduleAlias" -> card.moduleAlias();
            case "recordId" -> card.recordId();
            case "definitionId" -> card.definitionId();
            case "workflowVersionId", "definitionVersionId" -> card.workflowVersionId();
            case "instanceStatus" -> card.instanceStatus();
            case "approvalStatus" -> card.approvalStatus();
            case "nodeKey" -> card.nodeKey();
            case "taskKind" -> card.taskKind();
            case "taskStatus" -> card.taskStatus();
            case "actionCode" -> card.actionCode();
            case "assignmentKind" -> card.assignmentKind();
            case "overtimeStatus" -> card.overtimeStatus();
            case "readStatus" -> card.readStatus();
            case "startedAt" -> card.startedAt();
            case "receivedAt" -> card.receivedAt();
            case "completedAt" -> card.completedAt();
            case "lastOperatedAt" -> card.lastOperatedAt();
            case "dueAt" -> card.dueAt();
            case "addSignAt" -> card.addSignAt();
            case "addedByAddSign" -> card.addedByAddSign();
            case "addSignSourceNodeKey" -> card.addSignSourceNodeKey();
            case "addSignOperatorId" -> card.addSignOperatorId();
            case "originalAssigneeId" -> card.originalAssigneeId();
            case "delegatedFromUserId" -> card.delegatedFromUserId();
            case "delegatedToUserId" -> card.delegatedToUserId();
            case "principalCanProcess" -> card.principalCanProcess();
            case "delegationTaskCount" -> card.delegationTaskCount();
            case "submitterUserId" -> card.submitterUserId();
            default -> throw new PlatformException("unsupported workflow workbench sort field: " + field);
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private int compareSortValue(Comparable left, Comparable right, WorkflowSortDirection direction) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        int compared = left.compareTo(right);
        return direction == WorkflowSortDirection.ASC ? compared : -compared;
    }

    private boolean sameText(String expected, String actual) {
        return expected == null || expected.isBlank() || expected.equals(actual);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean same(Object expected, Object actual) {
        return expected == null || expected.equals(actual);
    }

    private boolean matchesNodeKey(String expected, WorkflowWorkbenchCard card) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        if (expected.equals(card.nodeKey())) {
            return true;
        }
        String currentNodeKeys = card.currentNodeKeys();
        if (currentNodeKeys == null || currentNodeKeys.isBlank()) {
            return false;
        }
        for (String nodeKey : currentNodeKeys.split("[,;\\s]+")) {
            if (expected.equals(nodeKey)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesReadStatus(WorkflowNoticeReadStatus expected, WorkflowNoticeReadStatus actual) {
        return expected == null || expected == WorkflowNoticeReadStatus.ALL || expected == actual;
    }

    private WorkflowNoticeReadStatus noticeReadStatus(WorkflowTask task) {
        if (task == null || task.getTaskKind() != WorkflowTaskKind.NOTICE) {
            return null;
        }
        if (task.getTaskStatus() == WorkflowTaskStatus.NOTICED) {
            return WorkflowNoticeReadStatus.READ;
        }
        if (task.getTaskStatus() == WorkflowTaskStatus.TODO) {
            return WorkflowNoticeReadStatus.UNREAD;
        }
        return null;
    }

    private String actionCode(WorkflowTask task) {
        if (task == null) {
            return null;
        }
        if (task.getDecision() != null && !task.getDecision().isBlank()) {
            return task.getDecision();
        }
        return task.getTaskStatus() == WorkflowTaskStatus.TRANSFERRED ? "transfer" : null;
    }

    private WorkflowOvertimeStatus overtimeStatus(WorkflowWorkbenchCard card) {
        return card.overtimeStatus() == null ? WorkflowOvertimeStatus.NORMAL : card.overtimeStatus();
    }

    private String doneStatCode(WorkflowWorkbenchCard card) {
        if (card.taskStatus() == WorkflowTaskStatus.REJECTED) {
            return "REJECTED";
        }
        if (card.taskStatus() == WorkflowTaskStatus.ROLLED_BACK) {
            return "ROLLED_BACK";
        }
        if (card.taskStatus() == WorkflowTaskStatus.TRANSFERRED) {
            return "TRANSFERRED";
        }
        return "DONE";
    }

    private <E extends Enum<E> & CodeTitleEnum> List<WorkflowWorkbenchStatItem> statsWithAll(long all,
                                                                                            E[] values,
                                                                                            Map<E, Long> counts) {
        java.util.ArrayList<WorkflowWorkbenchStatItem> items = new java.util.ArrayList<>();
        items.add(stat("ALL", "全部", all));
        for (E value : values) {
            items.add(stat(value, counts.getOrDefault(value, 0L)));
        }
        return List.copyOf(items);
    }

    private WorkflowWorkbenchStatItem stat(CodeTitleEnum value, long count) {
        return stat(((Enum<?>) value).name(), value.getTitle(), count);
    }

    private WorkflowWorkbenchStatItem stat(String code, String label, long count) {
        return new WorkflowWorkbenchStatItem(code, label, count);
    }

    private boolean inRange(Comparable<?> value, Comparable<?> from, Comparable<?> to) {
        if (from == null && to == null) {
            return true;
        }
        if (value == null) {
            return false;
        }
        return (from == null || compareRange(value, from) >= 0)
                && (to == null || compareRange(value, to) < 0);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private int compareRange(Comparable value, Comparable bound) {
        return value.compareTo(bound);
    }

    private <T> List<T> pageItems(List<T> items, PageRequest pageRequest) {
        int from = Math.min(pageRequest.getOffset(), items.size());
        int to = Math.min(from + pageRequest.getLimit(), items.size());
        return items.subList(from, to);
    }

    private String firstText(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new PlatformException(message);
        }
        return value;
    }
}
