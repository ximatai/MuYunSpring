package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class WorkflowManualRouteSelectionPolicy {
    private final WorkflowManualBranchSelectorResolver selectorResolver = new WorkflowManualBranchSelectorResolver();

    public Map<String, Set<String>> selectedRouteKeysBySubmitBranch(WorkflowRuntimeGraph graph,
                                                                    WorkflowInstance instance,
                                                                    String startNodeKey,
                                                                    String selectedRouteKey,
                                                                    String selectedReason,
                                                                    String operatorId) {
        return selectedRouteKeysBySubmitBranch(graph, instance, startNodeKey, List.of(),
                selectedRouteKey, selectedReason, operatorId, null);
    }

    public Map<String, Set<String>> selectedRouteKeysBySubmitBranch(WorkflowRuntimeGraph graph,
                                                                    WorkflowInstance instance,
                                                                    String startNodeKey,
                                                                    List<WorkflowManualRouteSelection> manualRouteSelections,
                                                                    String selectedRouteKey,
                                                                    String selectedReason,
                                                                    String operatorId) {
        return selectedRouteKeysBySubmitBranch(graph, instance, startNodeKey, manualRouteSelections, selectedRouteKey,
                selectedReason, operatorId, null);
    }

    public Map<String, Set<String>> selectedRouteKeysBySubmitBranch(WorkflowRuntimeGraph graph,
                                                                    WorkflowInstance instance,
                                                                    String startNodeKey,
                                                                    List<WorkflowManualRouteSelection> manualRouteSelections,
                                                                    String selectedRouteKey,
                                                                    String selectedReason,
                                                                    String operatorId,
                                                                    Map<String, Object> businessFacts) {
        boolean structured = hasManualRouteSelections(manualRouteSelections);
        String selectedKey = structured ? null : textOrNull(selectedRouteKey);
        Map<String, Set<String>> selectedByBranch = structured
                ? selectedSubmitRoutes(graph, startNodeKey, manualRouteSelections)
                : selectedKey == null ? Map.of() : selectedSubmitRoute(graph, startNodeKey, selectedKey);
        Set<String> branchNodeKeys = WorkflowManualBranchFrontier.plan(graph, List.of(), List.of(),
                List.of(WorkflowActivationTarget.of(startNodeKey)), selectedByBranch,
                requireBusinessFacts(graph, businessFacts)).reachedManualBranchNodeKeys();
        requireReachedSelections(selectedByBranch, branchNodeKeys);
        requireManualBranches(graph, instance, List.of(), List.of(), branchNodeKeys, selectedByBranch,
                startNodeKey, selectedReasonsByBranch(manualRouteSelections, selectedRouteKey, selectedReason),
                operatorId);
        return selectedByBranch;
    }

    public void requireCompletedBranchSelection(WorkflowInstance instance,
                                                List<WorkflowNodeInstance> nodes,
                                                List<WorkflowTask> tasks,
                                                String completedNodeKey,
                                                List<WorkflowRouteInstance> selectedInitialRoutes,
                                                String selectedRouteKey,
                                                String selectedReason,
                                                String operatorId) {
        requireCompletedBranchSelection(instance, nodes, tasks, completedNodeKey, selectedInitialRoutes,
                List.of(), selectedRouteKey, selectedReason, operatorId);
    }

    public void requireCompletedBranchSelection(WorkflowInstance instance,
                                                List<WorkflowNodeInstance> nodes,
                                                List<WorkflowTask> tasks,
                                                String completedNodeKey,
                                                List<WorkflowRouteInstance> selectedInitialRoutes,
                                                List<WorkflowManualRouteSelection> manualRouteSelections,
                                                String selectedRouteKey,
                                                String selectedReason,
                                                String operatorId) {
        WorkflowNodeInstance completedNode = nodesByKey(nodes).get(completedNodeKey);
        if (completedNode == null || completedNode.getNodeType() != WorkflowNodeType.BRANCH
                || routeMode(completedNode) != WorkflowRouteMode.MANUAL) {
            return;
        }
        String selectedKey = selectedRouteKeyForBranch(completedNodeKey, manualRouteSelections, selectedRouteKey);
        if (selectedKey == null || selectedInitialRoutes.stream()
                .noneMatch(route -> selectedKey.equals(route.getRouteKey()))) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-manual-route-selection-policy.select-a-route-for-the-manual-branch",
                    "workflow manual branch requires selected route: {value1}"), Map.of("value1", String.valueOf(completedNodeKey)));
        }
        requireManualSelection(instance, nodeDefinition(completedNode), nodes, tasks, completedNodeKey,
                selectedReasonForBranch(completedNodeKey, manualRouteSelections, selectedRouteKey, selectedReason),
                operatorId);
    }

    public Map<String, Set<String>> selectedRouteKeysByProgressionBranch(List<WorkflowRouteInstance> routes,
                                                                         List<WorkflowNodeInstance> nodes,
                                                                         WorkflowRuntimeGraph graph,
                                                                         WorkflowInstance instance,
                                                                         List<WorkflowTask> tasks,
                                                                         String completedNodeKey,
                                                                         List<WorkflowRouteInstance> selectedInitialRoutes,
                                                                         String selectedRouteKey,
                                                                         String selectedReason,
                                                                         String operatorId) {
        return selectedRouteKeysByProgressionBranch(routes, nodes, graph, instance, tasks, completedNodeKey,
                selectedInitialRoutes, List.of(), selectedRouteKey, selectedReason, operatorId, null);
    }

    public Map<String, Set<String>> selectedRouteKeysByProgressionBranch(List<WorkflowRouteInstance> routes,
                                                                         List<WorkflowNodeInstance> nodes,
                                                                         WorkflowRuntimeGraph graph,
                                                                         WorkflowInstance instance,
                                                                         List<WorkflowTask> tasks,
                                                                         String completedNodeKey,
                                                                         List<WorkflowRouteInstance> selectedInitialRoutes,
                                                                         List<WorkflowManualRouteSelection> manualRouteSelections,
                                                                         String selectedRouteKey,
                                                                         String selectedReason,
                                                                         String operatorId) {
        return selectedRouteKeysByProgressionBranch(routes, nodes, graph, instance, tasks, completedNodeKey,
                selectedInitialRoutes, manualRouteSelections, selectedRouteKey, selectedReason, operatorId, null);
    }

    public Map<String, Set<String>> selectedRouteKeysByProgressionBranch(List<WorkflowRouteInstance> routes,
                                                                         List<WorkflowNodeInstance> nodes,
                                                                         WorkflowRuntimeGraph graph,
                                                                         WorkflowInstance instance,
                                                                         List<WorkflowTask> tasks,
                                                                         String completedNodeKey,
                                                                         List<WorkflowRouteInstance> selectedInitialRoutes,
                                                                         List<WorkflowManualRouteSelection> manualRouteSelections,
                                                                         String selectedRouteKey,
                                                                         String selectedReason,
                                                                         String operatorId,
                                                                         Map<String, Object> businessFacts) {
        boolean structured = hasManualRouteSelections(manualRouteSelections);
        String selectedKey = structured ? null : textOrNull(selectedRouteKey);
        Map<String, Set<String>> selectedByBranch = structured
                ? selectedProgressionRoutes(routes, nodes, graph, selectedInitialRoutes, completedNodeKey,
                manualRouteSelections)
                : selectedProgressionRoute(routes, nodes, graph, selectedInitialRoutes, selectedKey);
        Set<String> branchNodeKeys = WorkflowManualBranchFrontier.plan(graph, nodes, routes,
                selectedInitialRoutes.stream().map(route -> new WorkflowActivationTarget(route.getTargetNodeKey(), route.getId())).toList(),
                selectedByBranch, requireBusinessFacts(graph, businessFacts)).reachedManualBranchNodeKeys();
        requireReachedSelections(selectedByBranch, branchNodeKeys);
        requireManualBranches(graph, instance, nodes, tasks, branchNodeKeys, selectedByBranch,
                completedNodeKey, selectedReasonsByBranch(manualRouteSelections, selectedRouteKey, selectedReason),
                operatorId);
        return selectedByBranch;
    }

    public String selectedRouteKeyForBranch(String branchNodeKey,
                                            List<WorkflowManualRouteSelection> manualRouteSelections,
                                            String fallbackSelectedRouteKey) {
        if (!hasManualRouteSelections(manualRouteSelections)) {
            return textOrNull(fallbackSelectedRouteKey);
        }
        return manualRouteSelections.stream()
                .filter(selection -> branchNodeKey.equals(textOrNull(selection.branchNodeKey())))
                .findFirst()
                .map(selection -> requireText(selection.routeKey(), TextReference.code(
                        "platform.rejection.workflow-manual-route-selection-policy.select-a-manual-branch-route",
                        "workflow manual branch selected route key must not be blank")))
                .orElse(null);
    }

    public String selectedReasonForRoute(WorkflowRouteInstance route,
                                         List<WorkflowManualRouteSelection> manualRouteSelections,
                                         String fallbackSelectedRouteKey,
                                         String fallbackSelectedReason) {
        if (!hasManualRouteSelections(manualRouteSelections)) {
            String selectedKey = textOrNull(fallbackSelectedRouteKey);
            return selectedKey != null && selectedKey.equals(route.getRouteKey()) ? fallbackSelectedReason : null;
        }
        return manualRouteSelections.stream()
                .filter(selection -> route.getSourceNodeKey().equals(textOrNull(selection.branchNodeKey())))
                .filter(selection -> route.getRouteKey().equals(textOrNull(selection.routeKey())))
                .findFirst()
                .map(WorkflowManualRouteSelection::selectedReason)
                .orElse(null);
    }

    private Map<String, Set<String>> selectedSubmitRoute(WorkflowRuntimeGraph graph,
                                                         String startNodeKey,
                                                         String selectedRouteKey) {
        return graph.links().values().stream()
                .filter(link -> selectedRouteKey.equals(link.getRouteKey()))
                .findFirst()
                .map(link -> {
                    requireManualBranch(graph, List.of(), link.getSourceNodeKey());
                    return Map.of(link.getSourceNodeKey(), Set.of(selectedRouteKey));
                })
                .orElseThrow(() -> PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-manual-route-selection-policy.the-selected-route-is-not-available",
                        "workflow selected route is not candidate outgoing route")));
    }

    public Map<String, Set<String>> selectedRoutesForPlanning(WorkflowRuntimeGraph graph,
                                                              List<WorkflowManualRouteSelection> selections,
                                                              String legacySelectedRouteKey) {
        if (hasManualRouteSelections(selections)) return selectedSubmitRoutes(graph, null, selections);
        String selectedKey = textOrNull(legacySelectedRouteKey);
        return selectedKey == null ? Map.of() : selectedSubmitRoute(graph, null, selectedKey);
    }

    private Map<String, Set<String>> selectedSubmitRoutes(WorkflowRuntimeGraph graph,
                                                          String startNodeKey,
                                                          List<WorkflowManualRouteSelection> manualRouteSelections) {
        Map<String, Set<String>> selected = new LinkedHashMap<>();
        for (WorkflowManualRouteSelection selection : manualRouteSelections) {
            String branchNodeKey = requireText(selection.branchNodeKey(), TextReference.code(
                    "platform.rejection.workflow-manual-route-selection-policy.enter-a-manual-branch-node-key",
                    "workflow manual branch node key must not be blank"));
            String routeKey = requireText(selection.routeKey(), TextReference.code(
                    "platform.rejection.workflow-manual-route-selection-policy.select-a-manual-branch-route",
                    "workflow manual branch selected route key must not be blank"));
            if (graph.outgoing(branchNodeKey).stream()
                    .noneMatch(link -> routeKey.equals(link.getRouteKey()))) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-manual-route-selection-policy.the-selected-route-is-not-available",
                        "workflow selected route is not candidate outgoing route"));
            }
            requireManualBranch(graph, List.of(), branchNodeKey);
            putSingleSelection(selected, branchNodeKey, routeKey);
        }
        return selected;
    }

    private Map<String, Set<String>> selectedProgressionRoute(List<WorkflowRouteInstance> routes,
                                                              List<WorkflowNodeInstance> nodes,
                                                              WorkflowRuntimeGraph graph,
                                                              List<WorkflowRouteInstance> selectedInitialRoutes,
                                                              String selectedRouteKey) {
        if (selectedRouteKey == null) {
            return Map.of();
        }
        WorkflowRouteInstance initialSelection = selectedInitialRoutes.stream()
                .filter(route -> selectedRouteKey.equals(route.getRouteKey())).findFirst().orElse(null);
        if (initialSelection != null) {
            // Ordinary nodes retain their direct continuation route compatibility.
            if (graph.requireNode(initialSelection.getSourceNodeKey()).getNodeType() == WorkflowNodeType.BRANCH) {
                requireManualBranch(graph, nodes, initialSelection.getSourceNodeKey());
            }
            return Map.of();
        }
        return routes.stream()
                .filter(route -> route.getRouteStatus() == WorkflowRouteStatus.CANDIDATE)
                .filter(route -> selectedRouteKey.equals(route.getRouteKey()))
                .findFirst()
                .map(route -> {
                    requireManualBranch(graph, nodes, route.getSourceNodeKey());
                    return Map.of(route.getSourceNodeKey(), Set.of(selectedRouteKey));
                })
                .orElseGet(Map::of);
    }

    private Map<String, Set<String>> selectedProgressionRoutes(List<WorkflowRouteInstance> routes,
                                                               List<WorkflowNodeInstance> nodes,
                                                               WorkflowRuntimeGraph graph,
                                                               List<WorkflowRouteInstance> selectedInitialRoutes,
                                                               String completedNodeKey,
                                                               List<WorkflowManualRouteSelection> manualRouteSelections) {
        Map<String, Set<String>> selected = new LinkedHashMap<>();
        for (WorkflowManualRouteSelection selection : manualRouteSelections) {
            String branchNodeKey = requireText(selection.branchNodeKey(), TextReference.code(
                    "platform.rejection.workflow-manual-route-selection-policy.enter-a-manual-branch-node-key",
                    "workflow manual branch node key must not be blank"));
            String routeKey = requireText(selection.routeKey(), TextReference.code(
                    "platform.rejection.workflow-manual-route-selection-policy.select-a-manual-branch-route",
                    "workflow manual branch selected route key must not be blank"));
            requireManualBranch(graph, nodes, branchNodeKey);
            if (branchNodeKey.equals(completedNodeKey)) {
                continue;
            }
            boolean candidate = routes.stream()
                    .anyMatch(route -> route.getRouteStatus() == WorkflowRouteStatus.CANDIDATE
                            && branchNodeKey.equals(route.getSourceNodeKey())
                            && routeKey.equals(route.getRouteKey()));
            if (!candidate) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-manual-route-selection-policy.the-selected-route-is-not-available",
                        "workflow selected route is not candidate outgoing route"));
            }
            putSingleSelection(selected, branchNodeKey, routeKey);
        }
        return selected;
    }

    private void requireManualBranch(WorkflowRuntimeGraph graph,
                                     List<WorkflowNodeInstance> nodes,
                                     String branchNodeKey) {
        WorkflowNodeDefinition definition = graph.requireNode(branchNodeKey);
        WorkflowNodeInstance runtimeNode = nodesByKey(nodes).get(branchNodeKey);
        if (definition.getNodeType() != WorkflowNodeType.BRANCH
                || routeMode(definition, runtimeNode) != WorkflowRouteMode.MANUAL) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-manual-route-selection-policy.route-selection-is-only-available-for-manual-branches",
                    "workflow manual route selection requires MANUAL branch: {value1}"), Map.of("value1", String.valueOf(branchNodeKey)));
        }
    }

    private Map<String, Object> requireBusinessFacts(WorkflowRuntimeGraph graph, Map<String, Object> facts) {
        if (facts != null) return facts;
        var formulas = new net.ximatai.muyun.spring.common.formula.FormulaEngine();
        boolean requiresFacts = graph.links().values().stream()
                .filter(link -> graph.requireNode(link.getSourceNodeKey()).getNodeType() == WorkflowNodeType.BRANCH)
                .filter(link -> routeMode(graph.requireNode(link.getSourceNodeKey()), null) == WorkflowRouteMode.AUTO)
                .map(WorkflowLinkDefinition::getConditionExpression)
                .filter(expression -> expression != null && !expression.isBlank())
                .anyMatch(expression -> !formulas.referencedFields(expression).isEmpty());
        if (requiresFacts) throw new PlatformException("workflow manual frontier requires business facts");
        return Map.of();
    }

    private void requireReachedSelections(Map<String, Set<String>> selections, Set<String> reached) {
        for (String branch : selections.keySet()) {
            if (!reached.contains(branch)) throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-manual-route-selection-policy.the-selected-route-is-not-available-details",
                    "workflow selected route is not candidate outgoing route: {value1}"), Map.of("value1", String.valueOf(branch)));
        }
    }

    private void requireManualBranches(WorkflowRuntimeGraph graph,
                                       WorkflowInstance instance,
                                       List<WorkflowNodeInstance> nodes,
                                       List<WorkflowTask> tasks,
                                       Set<String> branchNodeKeys,
                                       Map<String, Set<String>> selectedByBranch,
                                       String fallbackSelectorNodeKey,
                                       Map<String, String> selectedReasonsByBranch,
                                       String operatorId) {
        Map<String, WorkflowNodeInstance> runtimeNodes = nodesByKey(nodes);
        List<WorkflowNodeInstance> selectorNodes = (nodes == null || nodes.isEmpty()) ? graph.startNodes().stream().map(start -> {
            WorkflowNodeInstance selector = new WorkflowNodeInstance();
            selector.setNodeKey(start.getNodeKey());
            selector.setNodeType(WorkflowNodeType.START);
            return selector;
        }).toList() : nodes;
        for (String branchNodeKey : branchNodeKeys) {
            WorkflowNodeDefinition graphNode = graph.requireNode(branchNodeKey);
            WorkflowNodeInstance runtimeNode = runtimeNodes.get(branchNodeKey);
            if (routeMode(graphNode, runtimeNode) != WorkflowRouteMode.MANUAL) {
                continue;
            }
            Set<String> selected = selectedByBranch.get(branchNodeKey);
            if (selected == null || selected.isEmpty()) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-manual-route-selection-policy.select-a-route-for-the-manual-branch",
                        "workflow manual branch requires selected route: {value1}"), Map.of("value1", String.valueOf(branchNodeKey)));
            }
            WorkflowNodeDefinition governingNode = runtimeNode == null ? graphNode : nodeDefinition(runtimeNode);
            requireManualSelection(instance, governingNode, selectorNodes, tasks, fallbackSelectorNodeKey,
                    selectedReasonsByBranch.getOrDefault(branchNodeKey, selectedReasonsByBranch.get("")),
                    operatorId);
        }
    }

    private void requireManualSelection(WorkflowInstance instance,
                                        WorkflowNodeDefinition branchNode,
                                        List<WorkflowNodeInstance> nodes,
                                        List<WorkflowTask> tasks,
                                        String fallbackSelectorNodeKey,
                                        String selectedReason,
                                        String operatorId) {
        if (Boolean.TRUE.equals(branchNode.getRequireManualSelectionReason()) && textOrNull(selectedReason) == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-manual-route-selection-policy.enter-a-reason-for-the-manual-branch-selection",
                    "workflow manual branch selection reason is required: {value1}"), Map.of("value1", String.valueOf(branchNode.getNodeKey())));
        }
        String selectorNodeKey = textOrNull(branchNode.getSelectorNodeKey());
        if (selectorNodeKey == null) {
            selectorNodeKey = fallbackSelectorNodeKey;
        }
        selectorNodeKey = requireText(selectorNodeKey, TextReference.code(
                "platform.rejection.workflow-manual-route-selection-policy.enter-a-manual-branch-selector-node-key",
                "workflow manual branch selector node key must not be blank"));
        String validOperatorId = requireText(operatorId, TextReference.code(
                "platform.rejection.workflow-manual-route-selection-policy.select-a-workflow-operator",
                "workflow operator id must not be blank"));
        WorkflowManualBranchSelectorResolver.SelectorResolution resolution = selectorResolver.resolve(instance, nodes,
                tasks, selectorNodeKey, null, validOperatorId);
        if (!resolution.selectable()) {
            throw manualSelectorException(branchNode.getNodeKey(), resolution);
        }
    }

    private PlatformException manualSelectorException(
            String branchNodeKey,
            WorkflowManualBranchSelectorResolver.SelectorResolution resolution) {
        String selectorNodeKey = resolution.selectorNodeKey();
        return switch (resolution.unselectableReason()) {
            case WorkflowManualBranchSelectorResolver.SELECTOR_NOT_FOUND ->
                    PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.workflow-manual-route-selection-policy.manual-branch-selector-node-not-found",
                            "workflow manual branch selector node not found: {value1}"), Map.of("value1", String.valueOf(selectorNodeKey)));
            case WorkflowManualBranchSelectorResolver.SELECTOR_UNSUPPORTED -> PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-manual-route-selection-policy.the-selector-must-be-a-start-approval-or-task-node",
                    "workflow manual branch selector node must be start, approval or task: {value1}"), Map.of("value1", String.valueOf(selectorNodeKey)));
            case WorkflowManualBranchSelectorResolver.SELECTOR_NOT_PROCESSED -> PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-manual-route-selection-policy.the-selector-node-has-not-been-processed",
                    "workflow manual branch selector has no actual processor: {value1}"), Map.of("value1", String.valueOf(selectorNodeKey)));
            default -> PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-manual-route-selection-policy.you-are-not-the-selector-for-this-manual-branch",
                    "workflow manual branch selector must be operator: {value1}"), Map.of("value1", String.valueOf(branchNodeKey)));
        };
    }

    private WorkflowRouteMode routeMode(WorkflowNodeDefinition definition, WorkflowNodeInstance instance) {
        if (instance != null && instance.getRouteMode() != null) {
            return instance.getRouteMode();
        }
        return definition.getRouteMode() == null ? WorkflowRouteMode.AUTO : definition.getRouteMode();
    }

    private WorkflowRouteMode routeMode(WorkflowNodeInstance node) {
        return node.getRouteMode() == null ? WorkflowRouteMode.AUTO : node.getRouteMode();
    }

    private WorkflowNodeDefinition nodeDefinition(WorkflowNodeInstance node) {
        WorkflowNodeDefinition definition = new WorkflowNodeDefinition();
        definition.setNodeKey(node.getNodeKey());
        definition.setNodeType(node.getNodeType());
        definition.setRouteMode(node.getRouteMode());
        definition.setSelectorNodeKey(node.getSelectorNodeKey());
        definition.setRequireManualSelectionReason(node.getRequireManualSelectionReason());
        return definition;
    }

    private Map<String, WorkflowNodeInstance> nodesByKey(List<WorkflowNodeInstance> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return Map.of();
        }
        return nodes.stream()
                .collect(Collectors.toMap(WorkflowNodeInstance::getNodeKey, Function.identity(), (left, right) -> left,
                        LinkedHashMap::new));
    }

    private Map<String, String> selectedReasonsByBranch(List<WorkflowManualRouteSelection> manualRouteSelections,
                                                        String selectedRouteKey,
                                                        String selectedReason) {
        if (!hasManualRouteSelections(manualRouteSelections)) {
            if (textOrNull(selectedRouteKey) == null) {
                return Map.of();
            }
            Map<String, String> reasons = new LinkedHashMap<>();
            reasons.put("", selectedReason);
            return reasons;
        }
        Map<String, String> reasons = new LinkedHashMap<>();
        for (WorkflowManualRouteSelection selection : manualRouteSelections) {
            reasons.put(requireText(selection.branchNodeKey(), TextReference.code(
                    "platform.rejection.workflow-manual-route-selection-policy.enter-a-manual-branch-node-key",
                    "workflow manual branch node key must not be blank")),
                    selection.selectedReason());
        }
        return reasons;
    }

    private String selectedReasonForBranch(String branchNodeKey,
                                           List<WorkflowManualRouteSelection> manualRouteSelections,
                                           String selectedRouteKey,
                                           String selectedReason) {
        if (!hasManualRouteSelections(manualRouteSelections)) {
            return textOrNull(selectedRouteKey) == null ? null : selectedReason;
        }
        return manualRouteSelections.stream()
                .filter(selection -> branchNodeKey.equals(textOrNull(selection.branchNodeKey())))
                .findFirst()
                .map(WorkflowManualRouteSelection::selectedReason)
                .orElse(null);
    }

    private void putSingleSelection(Map<String, Set<String>> selected, String branchNodeKey, String routeKey) {
        if (selected.put(branchNodeKey, Set.of(routeKey)) != null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-manual-route-selection-policy.duplicate-manual-branch-selection",
                    "workflow manual branch has duplicate route selection: {value1}"), Map.of("value1", String.valueOf(branchNodeKey)));
        }
    }

    private boolean hasManualRouteSelections(List<WorkflowManualRouteSelection> manualRouteSelections) {
        return manualRouteSelections != null && !manualRouteSelections.isEmpty();
    }

    private String requireText(String value, TextReference source) {
        if (value == null || value.isBlank()) {
            throw PlatformErrors.badRequestText(source);
        }
        return value;
    }

    private String textOrNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
