package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowManualRouteSelectionPolicyTest {
    private final WorkflowManualRouteSelectionPolicy policy = new WorkflowManualRouteSelectionPolicy();

    @Test
    void submitRejectsBothLegacyAndStructuredAutoBranchOverrides() {
        var graph = graph(WorkflowRouteMode.AUTO);
        assertThatThrownBy(() -> policy.selectedRouteKeysBySubmitBranch(graph, instance(), "start",
                "forced", "override", "initiator")).isInstanceOf(PlatformException.class)
                .hasMessageContaining("requires MANUAL branch: branch");
        assertThatThrownBy(() -> policy.selectedRouteKeysBySubmitBranch(graph, instance(), "start",
                List.of(new WorkflowManualRouteSelection("branch", "forced", "override")), null, null, "initiator"))
                .isInstanceOf(PlatformException.class).hasMessageContaining("requires MANUAL branch: branch");
        assertThat(policy.selectedRouteKeysBySubmitBranch(graph, instance(), "start", null, null, "initiator"))
                .isEmpty();
    }

    @Test
    void manualSubmitRetainsSelectorAndReasonChecks() {
        var graph = graph(WorkflowRouteMode.MANUAL);
        graph.requireNode("branch").setRequireManualSelectionReason(true);
        assertThat(policy.selectedRouteKeysBySubmitBranch(graph, instance(), "start", "forced", "choose", "initiator"))
                .isEqualTo(Map.of("branch", Set.of("forced")));
        assertThatThrownBy(() -> policy.selectedRouteKeysBySubmitBranch(graph, instance(), "start", "forced", null, "initiator"))
                .hasMessageContaining("selection reason is required");
        assertThatThrownBy(() -> policy.selectedRouteKeysBySubmitBranch(graph, instance(), "start", "forced", "choose", "other"))
                .hasMessageContaining("selector must be operator");
    }

    @Test
    void customStartNodeKeyResolvesSubmitterForManualSelection() {
        var graph = graph(WorkflowRouteMode.MANUAL);
        graph.requireNode("start").setNodeKey("begin");
        graph.requireNode("branch").setSelectorNodeKey("begin");
        var rebuilt = WorkflowRuntimeGraph.of(List.copyOf(graph.nodes().values()),
                List.of(link("next", "begin", "branch"), link("forced", "branch", "left")));
        assertThat(policy.selectedRouteKeysBySubmitBranch(rebuilt, instance(), "begin", "forced", "choose", "initiator"))
                .isEqualTo(Map.of("branch", Set.of("forced")));
        assertThatThrownBy(() -> policy.selectedRouteKeysBySubmitBranch(rebuilt, instance(), "begin", "forced", "choose", "other"))
                .hasMessageContaining("selector must be operator");
    }

    @Test
    void progressionRejectsBothPayloadFormsAndRespectsFrozenRuntimeMode() {
        // The frozen runtime node governs selection even if a caller supplies a different design graph.
        var graph = graph(WorkflowRouteMode.MANUAL);
        var runtimeBranch = runtimeNode("branch", WorkflowNodeType.BRANCH);
        runtimeBranch.setRouteMode(WorkflowRouteMode.AUTO);
        var nodes = List.of(runtimeBranch);
        var initial = List.of(route("next", "start", "branch"));
        var routes = List.of(initial.getFirst(), route("forced", "branch", "left"));
        assertThatThrownBy(() -> policy.selectedRouteKeysByProgressionBranch(routes, nodes, graph, instance(),
                List.of(), "start", initial, "forced", "override", "initiator"))
                .hasMessageContaining("requires MANUAL branch: branch");
        assertThatThrownBy(() -> policy.selectedRouteKeysByProgressionBranch(routes, nodes, graph, instance(),
                List.of(), "start", initial, List.of(new WorkflowManualRouteSelection("branch", "forced", "override")),
                null, null, "initiator")).hasMessageContaining("requires MANUAL branch: branch");
    }

    @Test
    void ordinaryDirectContinuationStillAcceptsLegacySelectedRouteKey() {
        var graph = graph(WorkflowRouteMode.AUTO);
        var nodes = List.of(runtimeNode("branch", WorkflowNodeType.BRANCH));
        var initial = List.of(route("next", "start", "branch"));
        var routes = List.of(initial.getFirst(), route("forced", "branch", "left"));
        assertThat(policy.selectedRouteKeysByProgressionBranch(routes, nodes, graph, instance(), List.of(),
                "start", initial, "next", null, "initiator")).isEmpty();
    }

    @Test
    void legacyPolicyCannotPlanFactDependentAutoConditionsWithoutTheFactSnapshot() {
        var graph = graph(WorkflowRouteMode.AUTO);
        graph.link("forced").setConditionExpression("{amount} > 100");
        assertThatThrownBy(() -> policy.selectedRouteKeysBySubmitBranch(graph, instance(), "start", null, null, "initiator"))
                .hasMessageContaining("requires business facts");
        assertThat(policy.selectedRouteKeysBySubmitBranch(graph, instance(), "start", List.of(), null, null,
                "initiator", Map.of("amount", 200))).isEmpty();
    }

    @Test
    void completedAutoBranchCannotHideSelectionAsAnInitialRoute() {
        var graph = graph(WorkflowRouteMode.AUTO);
        var nodes = List.of(runtimeNode("branch", WorkflowNodeType.BRANCH));
        var initial = List.of(route("forced", "branch", "left"));
        assertThatThrownBy(() -> policy.selectedRouteKeysByProgressionBranch(initial, nodes, graph, instance(),
                List.of(), "branch", initial, "forced", null, "initiator"))
                .hasMessageContaining("requires MANUAL branch: branch");
        assertThatThrownBy(() -> policy.selectedRouteKeysByProgressionBranch(initial, nodes, graph, instance(),
                List.of(), "branch", initial, List.of(new WorkflowManualRouteSelection("branch", "forced", null)),
                null, null, "initiator")).hasMessageContaining("requires MANUAL branch: branch");
    }

    private WorkflowRuntimeGraph graph(WorkflowRouteMode mode) {
        var start = new WorkflowNodeDefinition(); start.setNodeKey("start"); start.setNodeType(WorkflowNodeType.START);
        var branch = new WorkflowNodeDefinition(); branch.setNodeKey("branch"); branch.setNodeType(WorkflowNodeType.BRANCH);
        branch.setRouteMode(mode); branch.setSelectorNodeKey("start");
        var left = new WorkflowNodeDefinition(); left.setNodeKey("left"); left.setNodeType(WorkflowNodeType.APPROVAL);
        return WorkflowRuntimeGraph.of(List.of(start, branch, left),
                List.of(link("next", "start", "branch"), link("forced", "branch", "left")));
    }

    private WorkflowLinkDefinition link(String key, String source, String target) {
        var link = new WorkflowLinkDefinition(); link.setRouteKey(key); link.setSourceNodeKey(source); link.setTargetNodeKey(target);
        return link;
    }

    private WorkflowNodeInstance runtimeNode(String key, WorkflowNodeType type) {
        var node = new WorkflowNodeInstance(); node.setNodeKey(key); node.setNodeType(type); return node;
    }

    private WorkflowRouteInstance route(String key, String source, String target) {
        var route = new WorkflowRouteInstance(); route.setRouteKey(key); route.setSourceNodeKey(source); route.setTargetNodeKey(target);
        route.setId(key);
        route.setRouteStatus(WorkflowRouteStatus.CANDIDATE); return route;
    }

    private WorkflowInstance instance() {
        var instance = new WorkflowInstance(); instance.setStartedBy("initiator"); return instance;
    }
}
