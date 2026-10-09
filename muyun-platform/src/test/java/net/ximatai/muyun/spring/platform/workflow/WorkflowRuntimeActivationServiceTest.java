package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowRuntimeActivationServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowRuntimeActivationService service = new WorkflowRuntimeActivationService();

    @Test
    void shouldStopAtApprovalNode() {
        WorkflowRuntimeGraph graph = WorkflowRuntimeGraph.of(
                List.of(node("start", WorkflowNodeType.START), node("approve", WorkflowNodeType.APPROVAL)),
                List.of(link("r1", "start", "approve", false))
        );

        WorkflowActivationResult result = service.activate(WorkflowActivationRequest.from(graph, "start"));

        assertThat(result.activatedNodeKeys()).containsExactly("start", "approve");
        assertThat(result.traversedRouteKeys()).containsExactly("r1");
        assertThat(result.blockingApprovalNodeKeys()).containsExactly("approve");
        assertThat(result.completed()).isFalse();
    }

    @Test
    void shouldContinueAfterApprovalCompletedMilestone() {
        WorkflowNodeDefinition milestone = node("approvalDone", WorkflowNodeType.MILESTONE);
        milestone.setMilestoneType(WorkflowMilestoneType.APPROVAL_COMPLETED);
        WorkflowRuntimeGraph graph = WorkflowRuntimeGraph.of(
                List.of(node("start", WorkflowNodeType.START), milestone, node("businessTask", WorkflowNodeType.TASK)),
                List.of(link("r1", "start", "approvalDone", false),
                        link("r2", "approvalDone", "businessTask", false))
        );

        WorkflowActivationResult result = service.activate(WorkflowActivationRequest.from(graph, "start"));

        assertThat(result.approvalCompleted()).isTrue();
        assertThat(result.blockingTaskNodeKeys()).containsExactly("businessTask");
        assertThat(result.completed()).isFalse();
    }

    @Test
    void shouldSelectDefaultBranchRouteWhenNoManualSelectionProvided() {
        WorkflowRuntimeGraph graph = WorkflowRuntimeGraph.of(
                List.of(node("branch", WorkflowNodeType.BRANCH),
                        node("left", WorkflowNodeType.TASK),
                        node("right", WorkflowNodeType.TASK)),
                List.of(link("leftRoute", "branch", "left", false),
                        link("rightRoute", "branch", "right", true))
        );

        graph.outgoing("branch").getFirst().setConditionExpression("false");
        WorkflowActivationResult result = service.activate(WorkflowActivationRequest.from(graph, "branch"));

        assertThat(result.traversedRouteKeys()).containsExactly("rightRoute");
        assertThat(result.blockingTaskNodeKeys()).containsExactly("right");
    }

    @Test
    void shouldUseExplicitBranchSelection() {
        WorkflowNodeDefinition branch = node("branch", WorkflowNodeType.BRANCH);
        branch.setRouteMode(WorkflowRouteMode.MANUAL);
        WorkflowRuntimeGraph graph = WorkflowRuntimeGraph.of(
                List.of(branch,
                        node("left", WorkflowNodeType.TASK),
                        node("right", WorkflowNodeType.TASK)),
                List.of(link("leftRoute", "branch", "left", false),
                        link("rightRoute", "branch", "right", true))
        );
        WorkflowActivationRequest request = new WorkflowActivationRequest(graph, List.of(WorkflowActivationTarget.of("branch")),
                Map.of("branch", Set.of("leftRoute")), Set.of(), 512);

        WorkflowActivationResult result = service.activate(request);

        assertThat(result.traversedRouteKeys()).containsExactly("leftRoute");
        assertThat(result.blockingTaskNodeKeys()).containsExactly("left");
    }

    @Test
    void shouldRejectManualOverrideOfAutoBranchEvenWhenItsConditionWouldMatch() {
        for (WorkflowRouteMode mode : new WorkflowRouteMode[]{null, WorkflowRouteMode.AUTO}) {
            WorkflowNodeDefinition branch = node("branch", WorkflowNodeType.BRANCH);
            branch.setRouteMode(mode);
            WorkflowRuntimeGraph graph = WorkflowRuntimeGraph.of(
                    List.of(branch, node("left", WorkflowNodeType.TASK)),
                    List.of(link("leftRoute", "branch", "left", false)));
            graph.outgoing("branch").getFirst().setConditionExpression("true");
            var request = new WorkflowActivationRequest(graph, List.of(WorkflowActivationTarget.of("branch")),
                    Map.of("branch", Set.of("leftRoute")), Set.of(), 512);
            assertThatThrownBy(() -> service.activate(request)).isInstanceOf(PlatformException.class)
                    .hasMessageContaining("requires MANUAL branch: branch");
        }
    }

    @Test
    void shouldActivateEveryMatchingAutoRouteAndExcludeDefault() {
        WorkflowRuntimeGraph graph = WorkflowRuntimeGraph.of(
                List.of(node("branch", WorkflowNodeType.BRANCH), node("left", WorkflowNodeType.TASK),
                        node("right", WorkflowNodeType.TASK), node("fallback", WorkflowNodeType.TASK)),
                List.of(link("leftRoute", "branch", "left", false),
                        link("rightRoute", "branch", "right", false), link("fallbackRoute", "branch", "fallback", true)));
        graph.outgoing("branch").stream().filter(route -> !route.getDefaultRoute())
                .forEach(route -> route.setConditionExpression("true"));
        var result = service.activate(WorkflowActivationRequest.from(graph, "branch"));
        assertThat(result.traversedRouteKeys()).containsExactly("leftRoute", "rightRoute");
        assertThat(result.blockingTaskNodeKeys()).containsExactly("left", "right");
    }

    @Test
    void shouldRejectNonBooleanAutoConditionWithoutSilentlyTakingTheDefault() {
        var graph = WorkflowRuntimeGraph.of(List.of(node("branch", WorkflowNodeType.BRANCH),
                        node("left", WorkflowNodeType.TASK), node("fallback", WorkflowNodeType.TASK)),
                List.of(link("leftRoute", "branch", "left", false), link("fallbackRoute", "branch", "fallback", true)));
        for (var expression : List.of("{status}", "1", "'true'", "{missing}")) {
            graph.link("leftRoute").setConditionExpression(expression);
            var request = new WorkflowActivationRequest(graph, List.of(WorkflowActivationTarget.of("branch")),
                    Map.of(), Set.of(), 512, Map.of("status", "REJECTED"));
            assertThatThrownBy(() -> service.activate(request)).isInstanceOf(PlatformException.class)
                    .hasMessageContaining("必须返回布尔值");
        }
    }

    @Test
    void shouldStopAtConvergeUntilRouteRuntimePassesIt() {
        WorkflowRuntimeGraph graph = WorkflowRuntimeGraph.of(
                List.of(node("join", WorkflowNodeType.CONVERGE), node("end", WorkflowNodeType.END)),
                List.of(link("done", "join", "end", false))
        );

        WorkflowActivationResult waiting = service.activate(WorkflowActivationRequest.from(graph, "join"));
        WorkflowActivationResult passed = service.activate(new WorkflowActivationRequest(graph,
                List.of(WorkflowActivationTarget.of("join")),
                Map.of(), Set.of("join"), 512));

        assertThat(waiting.completed()).isFalse();
        assertThat(waiting.activatedNodeKeys()).containsExactly("join");
        assertThat(waiting.waitingConvergeNodeKeys()).containsExactly("join");
        assertThat(passed.completed()).isTrue();
        assertThat(passed.activatedNodeKeys()).containsExactly("join", "end");
    }

    @Test
    void shouldRejectDuplicateNodeKeys() {
        assertThatThrownBy(() -> WorkflowRuntimeGraph.of(
                List.of(node("same", WorkflowNodeType.START), node("same", WorkflowNodeType.END)),
                List.of()
        )).isInstanceOf(PlatformException.class);
    }

    @Test
    void shouldRejectDanglingLinksBeforeActivation() {
        assertThatThrownBy(() -> WorkflowRuntimeGraph.of(
                List.of(node("start", WorkflowNodeType.START)),
                List.of(link("r1", "start", "missing", false))
        )).isInstanceOf(PlatformException.class)
                .hasMessageContaining("target node does not exist");

        assertThatThrownBy(() -> WorkflowRuntimeGraph.of(
                List.of(node("end", WorkflowNodeType.END)),
                List.of(link("r1", "missing", "end", false))
        )).isInstanceOf(PlatformException.class)
                .hasMessageContaining("source node does not exist");
    }

    @Test
    void shouldIndexIncomingOutgoingRouteAndStartNodes() {
        WorkflowNodeDefinition start = node("start", WorkflowNodeType.START);
        WorkflowLinkDefinition link = link("r1", "start", "task", false);

        WorkflowRuntimeGraph graph = WorkflowRuntimeGraph.of(
                List.of(start, node("task", WorkflowNodeType.TASK)),
                List.of(link)
        );

        assertThat(graph.startNodes()).containsExactly(start);
        assertThat(graph.link("r1")).isEqualTo(link);
        assertThat(graph.outgoing("start")).containsExactly(link);
        assertThat(graph.incoming("task")).containsExactly(link);
    }

    private WorkflowNodeDefinition node(String key, WorkflowNodeType type) {
        WorkflowNodeDefinition node = new WorkflowNodeDefinition();
        node.setNodeKey(key);
        node.setNodeType(type);
        return node;
    }

    private WorkflowLinkDefinition link(String key, String source, String target, boolean defaultRoute) {
        WorkflowLinkDefinition link = new WorkflowLinkDefinition();
        link.setRouteKey(key);
        link.setSourceNodeKey(source);
        link.setTargetNodeKey(target);
        link.setDefaultRoute(defaultRoute);
        return link;
    }
}
