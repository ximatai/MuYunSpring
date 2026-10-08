package net.ximatai.muyun.spring.platform.workflow;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class WorkflowRuntimeManualTopologyTest {
    @Test void anAddedApprovalCannotSeparateAManualDecisionFromItsFrozenSelector() {
        var graph = graph(WorkflowApprovalMode.ALL, "first");
        assertThatThrownBy(() -> WorkflowDesignCompiler.validateManualSelectionTopology(graph, graph.requireNode("branch")))
                .hasMessageContaining("本轮可达路径").hasMessageContaining("branch").hasMessageContaining("first");
    }

    @Test void anUnblockingNoticePreservesTheOriginalDecisionAction() {
        var graph = graph(WorkflowApprovalMode.NOTICE, "first");
        assertThatCode(() -> WorkflowDesignCompiler.validateManualSelectionTopology(graph, graph.requireNode("branch")))
                .doesNotThrowAnyException();
    }

    @Test void anExplicitSelectorOnTheLastApprovalRetainsItsOwnDecisionResponsibility() {
        var graph = graph(WorkflowApprovalMode.ALL, "inserted");
        assertThatCode(() -> WorkflowDesignCompiler.validateManualSelectionTopology(graph, graph.requireNode("branch")))
                .doesNotThrowAnyException();
    }

    private WorkflowRuntimeGraph graph(WorkflowApprovalMode addedMode, String selector) {
        var start = node("start", WorkflowNodeType.START);
        var first = node("first", WorkflowNodeType.APPROVAL); first.setApprovalMode(WorkflowApprovalMode.ALL);
        var inserted = node("inserted", WorkflowNodeType.APPROVAL); inserted.setApprovalMode(addedMode);
        var branch = node("branch", WorkflowNodeType.BRANCH); branch.setRouteMode(WorkflowRouteMode.MANUAL); branch.setSelectorNodeKey(selector);
        return WorkflowRuntimeGraph.of(List.of(start, first, inserted, branch), List.of(
                link("start", "first"), link("first", "inserted"), link("inserted", "branch")));
    }
    private WorkflowNodeDefinition node(String key, WorkflowNodeType type) {
        var node = new WorkflowNodeDefinition(); node.setNodeKey(key); node.setNodeType(type); return node;
    }
    private WorkflowLinkDefinition link(String source, String target) {
        var route = new WorkflowLinkDefinition(); route.setRouteKey(source + "-" + target);
        route.setSourceNodeKey(source); route.setTargetNodeKey(target); return route;
    }
}
