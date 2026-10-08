package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@Service
public class WorkflowSubmitReadFacade {
    private static final PageRequest ONE = new PageRequest(0, 1);

    private final WorkflowInstanceDao instanceDao;
    private final WorkflowDefinitionSelector selector;
    private final WorkflowSubmitFacade submitFacade;
    private final List<WorkflowModuleRecordGuard> recordGuards;
    private final WorkflowUserTitleResolver userTitles;
    private final WorkflowConditionService conditions;

    public WorkflowSubmitReadFacade(WorkflowInstanceDao instanceDao,
                                    WorkflowDefinitionSelector selector,
                                    WorkflowSubmitFacade submitFacade, WorkflowConditionService conditions) {
        this(instanceDao, selector, submitFacade, List.of(), conditions);
    }

    public WorkflowSubmitReadFacade(WorkflowInstanceDao instanceDao,
                                    WorkflowDefinitionSelector selector,
                                    WorkflowSubmitFacade submitFacade,
                                    List<WorkflowModuleRecordGuard> recordGuards, WorkflowConditionService conditions) {
        this(instanceDao, selector, submitFacade, recordGuards, WorkflowUserTitleResolver.NONE, conditions);
    }

    @Autowired
    public WorkflowSubmitReadFacade(WorkflowInstanceDao instanceDao, WorkflowDefinitionSelector selector,
            WorkflowSubmitFacade submitFacade, List<WorkflowModuleRecordGuard> recordGuards,
            org.springframework.beans.factory.ObjectProvider<WorkflowUserTitleResolver> titles, WorkflowConditionService conditions) {
        this(instanceDao, selector, submitFacade, recordGuards, titles.getIfAvailable(() -> WorkflowUserTitleResolver.NONE), conditions);
    }

    public WorkflowSubmitReadFacade(WorkflowInstanceDao instanceDao, WorkflowDefinitionSelector selector,
            WorkflowSubmitFacade submitFacade, List<WorkflowModuleRecordGuard> recordGuards,
            WorkflowUserTitleResolver titles, WorkflowConditionService conditions) {
        this.conditions = java.util.Objects.requireNonNull(conditions);
        this.userTitles = titles;
        this.instanceDao = instanceDao;
        this.selector = selector;
        this.submitFacade = submitFacade;
        this.recordGuards = recordGuards == null ? List.of() : List.copyOf(recordGuards);
    }

    public WorkflowSubmitStatusView status(WorkflowSubmitRequest request) {
        WorkflowSubmitRequest normalized = normalizeApprovalRequest(request);
        recordGuards.forEach(guard -> guard.requireRecordAction(normalized.moduleAlias(), normalized.recordId(),
                net.ximatai.muyun.spring.common.platform.PlatformAction.VIEW.executionPolicy()));
        WorkflowInstance current = currentInstance(normalized.moduleAlias(), normalized.recordId());
        if (current != null) {
            return WorkflowSubmitStatusView.current(current, normalized.operatorId() == null
                    ? net.ximatai.muyun.spring.common.identity.CurrentUserContext.currentUser().map(user -> user.userId()).orElse(null) : normalized.operatorId());
        }
        try {
            return WorkflowSubmitStatusView.unsubmitted(normalized.moduleAlias(), normalized.recordId(),
                    selector.select(normalized));
        } catch (PlatformException exception) {
            if (exception.getMessage() != null
                    && exception.getMessage().startsWith("published workflow definition not found:")) {
                return WorkflowSubmitStatusView.noWorkflow(normalized.moduleAlias(), normalized.recordId(),
                        exception.getMessage());
            }
            return WorkflowSubmitStatusView.matchError(normalized.moduleAlias(), normalized.recordId(),
                    exception.getMessage());
        }
    }

    public WorkflowSubmitPreviewView preview(WorkflowSubmitRequest request) {
        var preview = WorkflowSubmitPreviewView.of(submitFacade.preview(normalizeApprovalRequest(request)));
        var ids = preview.tasks().stream().flatMap(task -> java.util.stream.Stream.of(task.getAssigneeId(), task.getOriginalAssigneeId(), task.getDelegatedFromUserId(), task.getDelegatedToUserId())).filter(java.util.Objects::nonNull).distinct().toList();
        return preview.withTaskViews(userTitles.titles(ids));
    }

    public List<WorkflowManualBranchCandidateView> manualBranches(WorkflowSubmitRequest request) {
        WorkflowSubmitRequest normalized = normalizeApprovalRequest(request);
        recordGuards.forEach(guard -> guard.beforeSubmit(normalized));
        var selection = selector.select(normalized);
        var graph = WorkflowRuntimeGraph.of(selection.nodes(), selection.links());
        var businessFacts = conditions.businessFacts(selection.links().stream()
                .filter(link -> !Boolean.TRUE.equals(link.getDefaultRoute()))
                .map(WorkflowLinkDefinition::getConditionExpression).toList(), normalized.moduleAlias(), normalized.recordId());
        var frontier = WorkflowManualBranchFrontier.plan(graph, List.of(), List.of(),
                graph.startNodes().stream().map(node -> WorkflowActivationTarget.of(node.getNodeKey())).toList(),
                new WorkflowManualRouteSelectionPolicy().selectedRoutesForPlanning(graph, normalized.manualRouteSelections(), null), businessFacts);
        var nodes = selection.nodes().stream().collect(java.util.stream.Collectors.toMap(WorkflowNodeDefinition::getNodeKey, node -> node));
        var branches = selection.nodes().stream().filter(node -> node.getNodeType() == WorkflowNodeType.BRANCH
                && node.getRouteMode() == WorkflowRouteMode.MANUAL)
                .filter(node -> frontier.reachedManualBranchNodeKeys().contains(node.getNodeKey()))
                .filter(node -> nodes.containsKey(node.getSelectorNodeKey()) && nodes.get(node.getSelectorNodeKey()).getNodeType() == WorkflowNodeType.START)
                .toList();
        var branchKeys = branches.stream().map(WorkflowNodeDefinition::getNodeKey).collect(java.util.stream.Collectors.toSet());
        Map<String, String> expressions = new LinkedHashMap<>();
        selection.links().stream().filter(link -> branchKeys.contains(link.getSourceNodeKey()))
                .filter(link -> !Boolean.TRUE.equals(link.getDefaultRoute()))
                .forEach(link -> expressions.put(link.getRouteKey(), link.getConditionExpression()));
        var matched = conditions.manualMatches(expressions, businessFacts);
        return branches.stream().map(node -> {
            var outgoing = selection.links().stream().filter(link -> node.getNodeKey().equals(link.getSourceNodeKey())).toList();
            boolean anyMatched = outgoing.stream().anyMatch(link -> Boolean.TRUE.equals(matched.get(link.getRouteKey())));
            boolean known = outgoing.stream().filter(link -> !Boolean.TRUE.equals(link.getDefaultRoute()))
                    .allMatch(link -> matched.get(link.getRouteKey()) != null);
            var candidates = outgoing.stream().map(link -> {
                var target = nodes.get(link.getTargetNodeKey());
                String targetTitle = firstText(target == null ? null : target.getTitle(), link.getTargetNodeKey());
                boolean defaultRoute = Boolean.TRUE.equals(link.getDefaultRoute());
                Boolean conditionMatched = defaultRoute ? Boolean.FALSE : matched.get(link.getRouteKey());
                return new WorkflowManualBranchCandidateView.Candidate(null, link.getRouteKey(), link.getTargetNodeKey(),
                        target == null ? null : target.getNodeType(), WorkflowRouteStatus.CANDIDATE, link.getDefaultRoute(),
                        firstText(link.getTitle(), targetTitle), targetTitle, conditionMatched,
                        known && (defaultRoute ? !anyMatched : Boolean.TRUE.equals(conditionMatched)));
            }).toList();
            return new WorkflowManualBranchCandidateView(node.getNodeKey(), firstText(node.getTitle(), node.getNodeKey()),
                    node.getRouteMode(), node.getSelectorNodeKey(), node.getRequireManualSelectionReason(), candidates,
                    frontier.pendingManualBranchNodeKeys().contains(node.getNodeKey()));
        }).toList();
    }

    private String firstText(String title, String fallback) {
        return title == null || title.isBlank() ? fallback : title;
    }

    private WorkflowInstance currentInstance(String moduleAlias, String recordId) {
        return instanceDao.query(WorkflowTenantScope.criteria()
                        .eq("moduleAlias", moduleAlias)
                        .eq("recordId", recordId),
                ONE, Sort.desc("startedAt"), Sort.desc("createdAt")).stream().findFirst().orElse(null);
    }

    private WorkflowSubmitRequest normalizeApprovalRequest(WorkflowSubmitRequest request) {
        if (request == null) {
            throw new PlatformException("workflow submit request must not be null");
        }
        return WorkflowSubmitRequest.approval(request.moduleAlias(), request.recordId())
                .withAuthOrgId(request.authOrgId() == null ? net.ximatai.muyun.spring.common.identity.CurrentUserContext.currentUser().map(user -> user.organizationId()).orElse(null) : request.authOrgId())
                .withOperator(request.operatorId())
                .withOperatedAt(request.operatedAt())
                .withSelectedRoute(request.selectedRouteKey(), request.selectedReason())
                .withManualRouteSelections(request.manualRouteSelections());
    }
}
