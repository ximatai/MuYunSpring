package net.ximatai.muyun.spring.platform.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowManualBranchFrontierTest {
    @Test
    void followsCurrentAutoConditionsAndDoesNotExposeUnmatchedManualBranches() {
        var graph = WorkflowRuntimeGraph.of(List.of(node("start", WorkflowNodeType.START), auto("auto", "join"),
                manual("high"), manual("low"), node("a", WorkflowNodeType.APPROVAL), node("b", WorkflowNodeType.APPROVAL),
                converge("join", WorkflowConvergeMode.ALL, null)),
                List.of(link("start-auto", "start", "auto", null), link("high-route", "auto", "high", "{amount} > 100"),
                        link("low-route", "auto", "low", "{amount} <= 100"), link("high-a", "high", "a", null), link("low-b", "low", "b", null)));
        assertThat(plan(graph, Map.of(), Map.of("amount", 200)).pendingManualBranchNodeKeys()).containsExactly("high");
        assertThat(plan(graph, Map.of(), Map.of("amount", 50)).pendingManualBranchNodeKeys()).containsExactly("low");
    }

    @Test
    void providedManualSelectionRevealsTheNextManualFrontierAndStopsAtRealBusinessTasks() {
        var graph = WorkflowRuntimeGraph.of(List.of(node("start", WorkflowNodeType.START), manual("outer"), manual("inner"),
                node("task", WorkflowNodeType.TASK), manual("afterTask"), node("other", WorkflowNodeType.APPROVAL)),
                List.of(link("start-outer", "start", "outer", null), link("outer-inner", "outer", "inner", null),
                        link("outer-other", "outer", "other", null), link("inner-task", "inner", "task", null),
                        link("task-after", "task", "afterTask", null)));
        assertThat(plan(graph, Map.of(), Map.of()).pendingManualBranchNodeKeys()).containsExactly("outer");
        var second = plan(graph, Map.of("outer", Set.of("outer-inner")), Map.of());
        assertThat(second.reachedManualBranchNodeKeys()).containsExactlyInAnyOrder("outer", "inner");
        assertThat(second.pendingManualBranchNodeKeys()).containsExactly("inner");
        var chosen = plan(graph, Map.of("outer", Set.of("outer-inner"), "inner", Set.of("inner-task")), Map.of());
        assertThat(chosen.pendingManualBranchNodeKeys()).isEmpty();
        assertThat(chosen.reachedManualBranchNodeKeys()).doesNotContain("afterTask");
        assertThat(plan(graph, Map.of("outer", Set.of("outer-other")), Map.of()).reachedManualBranchNodeKeys()).containsExactly("outer");
    }

    @Test
    void ordinaryApprovalBlocksButNoticeContinues() {
        var approve = node("approve", WorkflowNodeType.APPROVAL);
        var graph = WorkflowRuntimeGraph.of(List.of(node("start", WorkflowNodeType.START), approve, manual("choice")),
                List.of(link("start-approve", "start", "approve", null), link("approve-choice", "approve", "choice", null)));
        assertThat(plan(graph, Map.of(), Map.of()).pendingManualBranchNodeKeys()).isEmpty();
        approve.setApprovalMode(WorkflowApprovalMode.NOTICE);
        assertThat(plan(graph, Map.of(), Map.of()).pendingManualBranchNodeKeys()).containsExactly("choice");
    }

    @Test
    void usesEffectivePathDenominatorAndDropsPendingBranchesWhenAnyOrRatioConverges() {
        for (var mode : WorkflowConvergeMode.values()) {
            for (int ratio : List.of(50, 100)) {
                var graph = convergenceGraph(mode, ratio);
                var result = plan(graph, Map.of(), Map.of());
                boolean passes = mode == WorkflowConvergeMode.ANY || mode == WorkflowConvergeMode.RATIO && ratio == 50;
                assertThat(result.pendingManualBranchNodeKeys()).containsExactly(passes ? "afterJoin" : "rightChoice");
                assertThat(result.reachedManualBranchNodeKeys()).doesNotContain(passes ? "rightChoice" : "afterJoin");
            }
        }
    }

    @Test
    void planningDoesNotChangeGraphOrFrozenRuntimeSnapshotsIncludingConvergenceState() {
        var graph = convergenceGraph(WorkflowConvergeMode.ANY, 50);
        var copiedNodes = graph.nodes().values().stream().map(source -> {
            var copy = new WorkflowNodeDefinition(); BeanUtils.copyProperties(source, copy); return copy;
        }).collect(java.util.stream.Collectors.toMap(WorkflowNodeDefinition::getNodeKey, java.util.function.Function.identity()));
        var copiedLinks = graph.links().values().stream().map(source -> {
            var copy = new WorkflowLinkDefinition(); BeanUtils.copyProperties(source, copy); return copy;
        }).collect(java.util.stream.Collectors.toMap(WorkflowLinkDefinition::getRouteKey, java.util.function.Function.identity()));
        var originalGraph = new WorkflowRuntimeGraph(copiedNodes, copiedLinks,
                graph.incomingLinks().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().stream().map(link -> copiedLinks.get(link.getRouteKey())).toList())),
                graph.outgoingLinks().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().stream().map(link -> copiedLinks.get(link.getRouteKey())).toList())));
        var nodes = graph.nodes().values().stream().map(source -> {
            var node = new WorkflowNodeInstance(); BeanUtils.copyProperties(source, node); node.setId(source.getNodeKey());
            node.setNodeStatus(WorkflowNodeStatus.WAITING); return node;
        }).toList();
        var routes = graph.links().values().stream().map(source -> {
            var route = new WorkflowRouteInstance(); BeanUtils.copyProperties(source, route); route.setId(source.getRouteKey());
            route.setRouteStatus(WorkflowRouteStatus.CANDIDATE); return route;
        }).toList();
        var savedNodes = nodes.stream().map(source -> { var copy = new WorkflowNodeInstance(); BeanUtils.copyProperties(source, copy); return copy; }).toList();
        var savedRoutes = routes.stream().map(source -> { var copy = new WorkflowRouteInstance(); BeanUtils.copyProperties(source, copy); return copy; }).toList();
        assertThat(WorkflowManualBranchFrontier.plan(graph, nodes, routes, List.of(WorkflowActivationTarget.of("start")),
                Map.of(), Map.of()).pendingManualBranchNodeKeys()).containsExactly("afterJoin");
        assertThat(nodes).usingRecursiveComparison().isEqualTo(savedNodes);
        assertThat(routes).usingRecursiveComparison().isEqualTo(savedRoutes);
        assertThat(graph).usingRecursiveComparison().isEqualTo(originalGraph);
        assertThat(WorkflowManualBranchFrontier.frozenGraph(nodes, routes)).usingRecursiveComparison()
                .ignoringFieldsMatchingRegexes(".*\\.id").ignoringCollectionOrder().isEqualTo(graph);
    }

    private WorkflowRuntimeGraph convergenceGraph(WorkflowConvergeMode mode, Integer ratio) {
        var right = manual("rightChoice"); right.setConvergeNodeKey("rightJoin");
        var edges = new ArrayList<>(List.of(link("start-auto", "start", "auto", null), link("left", "auto", "join", "true"),
                link("right", "auto", "rightChoice", "true"), link("right-a", "rightChoice", "a", null),
                link("right-b", "rightChoice", "b", null), link("a-join", "a", "rightJoin", null),
                link("b-join", "b", "rightJoin", null), link("right-parent", "rightJoin", "join", null),
                link("join-after", "join", "afterJoin", null)));
        return WorkflowRuntimeGraph.of(List.of(node("start", WorkflowNodeType.START), auto("auto", "join"), right,
                node("a", WorkflowNodeType.APPROVAL), node("b", WorkflowNodeType.APPROVAL), converge("rightJoin", WorkflowConvergeMode.ALL, null),
                converge("join", mode, ratio), manual("afterJoin")), edges);
    }

    private WorkflowManualBranchFrontier.Decision plan(WorkflowRuntimeGraph graph, Map<String, Set<String>> selections, Map<String, Object> facts) {
        return WorkflowManualBranchFrontier.plan(graph, List.of(), List.of(), List.of(WorkflowActivationTarget.of("start")), selections, facts);
    }

    private WorkflowNodeDefinition node(String key, WorkflowNodeType type) {
        var node = new WorkflowNodeDefinition(); node.setNodeKey(key); node.setNodeType(type); return node;
    }

    private WorkflowNodeDefinition manual(String key) { var node = node(key, WorkflowNodeType.BRANCH); node.setRouteMode(WorkflowRouteMode.MANUAL); return node; }
    private WorkflowNodeDefinition auto(String key, String converge) { var node = node(key, WorkflowNodeType.BRANCH); node.setRouteMode(WorkflowRouteMode.AUTO); node.setConvergeNodeKey(converge); return node; }
    private WorkflowNodeDefinition converge(String key, WorkflowConvergeMode mode, Integer ratio) {
        var node = node(key, WorkflowNodeType.CONVERGE); node.setConvergeMode(mode); node.setConvergeRatio(ratio); return node;
    }
    private WorkflowLinkDefinition link(String key, String source, String target, String condition) {
        var link = new WorkflowLinkDefinition(); link.setRouteKey(key); link.setSourceNodeKey(source); link.setTargetNodeKey(target);
        link.setConditionExpression(condition); return link;
    }
}
