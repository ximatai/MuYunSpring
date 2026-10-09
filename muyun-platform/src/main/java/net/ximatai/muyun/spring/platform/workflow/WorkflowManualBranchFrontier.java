package net.ximatai.muyun.spring.platform.workflow;

import org.springframework.beans.BeanUtils;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only branch planning uses the runtime's traversal and convergence semantics on detached scalar snapshots. */
public final class WorkflowManualBranchFrontier {
    private WorkflowManualBranchFrontier() { }

    public static WorkflowRuntimeGraph frozenGraph(List<WorkflowNodeInstance> nodes, List<WorkflowRouteInstance> routes) {
        return WorkflowRuntimeGraph.of(nodes.stream().map(source -> {
            var node = new WorkflowNodeDefinition(); BeanUtils.copyProperties(source, node); return node;
        }).toList(), routes.stream().map(source -> {
            var route = new WorkflowLinkDefinition(); BeanUtils.copyProperties(source, route); return route;
        }).toList());
    }

    public static Decision plan(WorkflowRuntimeGraph graph,
                                List<WorkflowNodeInstance> nodeRuns,
                                List<WorkflowRouteInstance> routeRuns,
                                List<WorkflowActivationTarget> targets,
                                Map<String, Set<String>> selectedByBranch,
                                Map<String, Object> businessFacts) {
        List<WorkflowNodeInstance> nodes = nodeRuns.isEmpty() ? graph.nodes().values().stream().map(definition -> {
            var node = new WorkflowNodeInstance(); BeanUtils.copyProperties(definition, node);
            node.setId("frontier-node:" + definition.getNodeKey()); node.setNodeStatus(WorkflowNodeStatus.WAITING);
            return node;
        }).toList() : nodeRuns.stream().map(source -> {
            var node = new WorkflowNodeInstance(); BeanUtils.copyProperties(source, node); return node;
        }).toList();
        List<WorkflowRouteInstance> routes = routeRuns.isEmpty() ? graph.links().values().stream().map(definition -> {
            var route = new WorkflowRouteInstance(); BeanUtils.copyProperties(definition, route);
            route.setId("frontier-route:" + definition.getRouteKey()); route.setRouteStatus(WorkflowRouteStatus.CANDIDATE);
            return route;
        }).toList() : routeRuns.stream().map(source -> {
            var route = new WorkflowRouteInstance(); BeanUtils.copyProperties(source, route); return route;
        }).toList();
        var activation = new WorkflowRuntimeActivationService() {
            @Override public WorkflowActivationResult activate(WorkflowActivationRequest request) {
                return activateUntilManualSelection(request);
            }
        };
        var result = new WorkflowActivationExecutor(activation, new WorkflowNodeInstanceStateService(),
                new WorkflowRouteInstanceStateService(), new WorkflowRouteRuntimeService()).execute(
                new WorkflowActivationRequest(graph, targets, selectedByBranch, Set.of(), 512, businessFacts),
                nodes, routes, "workflow-frontier", Instant.EPOCH);
        Set<String> reached = new LinkedHashSet<>();
        Set<String> pending = new LinkedHashSet<>();
        for (String key : result.activatedNodeKeys()) {
            var node = graph.requireNode(key);
            if (node.getNodeType() != WorkflowNodeType.BRANCH || node.getRouteMode() != WorkflowRouteMode.MANUAL) continue;
            reached.add(key);
            if (!selectedByBranch.containsKey(key) || selectedByBranch.get(key).isEmpty()) pending.add(key);
        }
        return new Decision(Set.copyOf(reached), Set.copyOf(pending));
    }

    public record Decision(Set<String> reachedManualBranchNodeKeys, Set<String> pendingManualBranchNodeKeys) { }
}
