package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.platform.ModuleRecordFacts;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class WorkflowSubmitDraftService {
    private final WorkflowInstanceSnapshotFactory snapshotFactory;
    private final WorkflowRuntimeActivationService activationService;
    private final WorkflowInstanceStateService instanceStateService;
    private final WorkflowNodeInstanceStateService nodeInstanceStateService;
    private final WorkflowRouteInstanceStateService routeInstanceStateService;
    private final WorkflowRouteRuntimeService routeRuntimeService;
    private final WorkflowRuntimeTaskFactory taskFactory;
    private final ModuleRecordFacts facts;
    private final WorkflowManualRouteSelectionPolicy manualRouteSelectionPolicy = new WorkflowManualRouteSelectionPolicy();

    public WorkflowSubmitDraftService(WorkflowInstanceSnapshotFactory snapshotFactory,
                                      WorkflowRuntimeActivationService activationService,
                                      WorkflowInstanceStateService instanceStateService,
                                      WorkflowNodeInstanceStateService nodeInstanceStateService,
                                      WorkflowRouteInstanceStateService routeInstanceStateService,
                                      WorkflowRouteRuntimeService routeRuntimeService,
                                      WorkflowRuntimeTaskFactory taskFactory, ModuleRecordFacts facts) {
        this.snapshotFactory = snapshotFactory;
        this.activationService = activationService;
        this.instanceStateService = instanceStateService;
        this.nodeInstanceStateService = nodeInstanceStateService;
        this.routeInstanceStateService = routeInstanceStateService;
        this.routeRuntimeService = routeRuntimeService;
        this.taskFactory = taskFactory;
        this.facts = facts;
    }

    public WorkflowSubmitDraft build(WorkflowDefinition definition,
                                     WorkflowVersion version,
                                     List<WorkflowNodeDefinition> nodeDefinitions,
                                     List<WorkflowLinkDefinition> linkDefinitions,
                                     String recordId,
                                     String operatorId,
                                     Instant operatedAt) {
        return build(definition, version, nodeDefinitions, linkDefinitions, recordId, null, operatorId, operatedAt,
                null, null);
    }

    public WorkflowSubmitDraft build(WorkflowDefinition definition,
                                     WorkflowVersion version,
                                     List<WorkflowNodeDefinition> nodeDefinitions,
                                     List<WorkflowLinkDefinition> linkDefinitions,
                                     String recordId,
                                     String operatorId,
                                     Instant operatedAt,
                                     String selectedRouteKey,
                                     String selectedReason) {
        return build(definition, version, nodeDefinitions, linkDefinitions, recordId, null, operatorId, operatedAt,
                selectedRouteKey, selectedReason);
    }

    public WorkflowSubmitDraft build(WorkflowDefinition definition,
                                     WorkflowVersion version,
                                     List<WorkflowNodeDefinition> nodeDefinitions,
                                     List<WorkflowLinkDefinition> linkDefinitions,
                                     String recordId,
                                     String authOrgId,
                                     String operatorId,
                                     Instant operatedAt) {
        return build(definition, version, nodeDefinitions, linkDefinitions, recordId, authOrgId, operatorId, operatedAt,
                null, null);
    }

    public WorkflowSubmitDraft build(WorkflowDefinition definition,
                                     WorkflowVersion version,
                                     List<WorkflowNodeDefinition> nodeDefinitions,
                                     List<WorkflowLinkDefinition> linkDefinitions,
                                     String recordId,
                                     String authOrgId,
                                     String operatorId,
                                     Instant operatedAt,
                                     String selectedRouteKey,
                                     String selectedReason) {
        return build(definition, version, nodeDefinitions, linkDefinitions, recordId, authOrgId, operatorId, operatedAt,
                selectedRouteKey, selectedReason, List.of());
    }

    public WorkflowSubmitDraft build(WorkflowDefinition definition,
                                     WorkflowVersion version,
                                     List<WorkflowNodeDefinition> nodeDefinitions,
                                     List<WorkflowLinkDefinition> linkDefinitions,
                                     String recordId,
                                     String authOrgId,
                                     String operatorId,
                                     Instant operatedAt,
                                     String selectedRouteKey,
                                     String selectedReason,
                                     List<WorkflowManualRouteSelection> manualRouteSelections) {
        WorkflowInstanceSnapshot snapshot = snapshotFactory.build(definition, version, nodeDefinitions,
                linkDefinitions, recordId, authOrgId, operatorId, operatedAt);
        WorkflowRuntimeGraph graph = WorkflowRuntimeGraph.of(nodeDefinitions, linkDefinitions);
        WorkflowNodeDefinition startNode = graph.startNodes().stream()
                .findFirst()
                .orElseThrow(() -> new PlatformException("workflow must contain a start node"));
        var businessFacts = linkDefinitions.stream().anyMatch(link -> link.getConditionExpression() != null && !link.getConditionExpression().isBlank())
                ? facts.read(definition.getModuleAlias(), recordId) : java.util.Map.<String, Object>of();
        Map<String, Set<String>> selectedRouteKeysByBranch =
                manualRouteSelectionPolicy.selectedRouteKeysBySubmitBranch(graph, snapshot.instance(),
                        startNode.getNodeKey(), manualRouteSelections, selectedRouteKey, selectedReason, operatorId, businessFacts);
        WorkflowActivationResult activation = new WorkflowActivationExecutor(activationService, nodeInstanceStateService,
                routeInstanceStateService, routeRuntimeService).execute(
                new WorkflowActivationRequest(graph, List.of(WorkflowActivationTarget.of(startNode.getNodeKey())),
                        selectedRouteKeysByBranch, Set.of(), 512, businessFacts), snapshot.nodes(), snapshot.routes(), operatorId, operatedAt);
        instanceStateService.applyActivation(snapshot.instance(), activation, operatedAt);
        nodeInstanceStateService.applyActivation(snapshot.nodes(), activation, operatedAt);
        routeInstanceStateService.applyActivation(snapshot.routes(), activation, operatorId, operatedAt);
        applyManualBranchSelection(snapshot.routes(), selectedRouteKeysByBranch, manualRouteSelections,
                selectedRouteKey, selectedReason, operatorId, operatedAt);
        WorkflowRuntimeTaskDraft taskDraft = taskFactory.createBlockingTasks(snapshot.instance(), snapshot.nodes(),
                activation, operatorId, operatedAt);

        List<WorkflowEvent> events = new ArrayList<>();
        events.addAll(snapshot.events());
        events.addAll(taskDraft.events());
        return new WorkflowSubmitDraft(snapshot.instance(), snapshot.nodes(), snapshot.routes(),
                taskDraft.tasks(), events, activation);
    }

    private void applyManualBranchSelection(List<WorkflowRouteInstance> routes,
                                            Map<String, Set<String>> selectedRouteKeysByBranch,
                                            List<WorkflowManualRouteSelection> manualRouteSelections,
                                            String selectedRouteKey,
                                            String selectedReason,
                                            String operatorId,
                                            Instant operatedAt) {
        if (selectedRouteKeysByBranch.isEmpty()) {
            return;
        }
        Instant now = operatedAt == null ? Instant.now() : operatedAt;
        for (Map.Entry<String, Set<String>> entry : selectedRouteKeysByBranch.entrySet()) {
            for (WorkflowRouteInstance route : routes) {
                if (!entry.getKey().equals(route.getSourceNodeKey())
                        || route.getRouteStatus() == WorkflowRouteStatus.INEFFECTIVE) {
                    continue;
                }
                if (entry.getValue().contains(route.getRouteKey())) {
                    routeRuntimeService.recordManualSelection(route, operatorId, now,
                            manualRouteSelectionPolicy.selectedReasonForRoute(route, manualRouteSelections,
                                    selectedRouteKey, selectedReason));
                } else if (route.getRouteStatus() == WorkflowRouteStatus.CANDIDATE) {
                    routeRuntimeService.ineffectiveRoute(route, WorkflowRouteReason.MANUAL_UNSELECTED,
                            operatorId, now);
                }
            }
        }
    }

}
