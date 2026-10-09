package net.ximatai.muyun.spring.platform.workflow;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class WorkflowActivationExecutorTest {
    private final Instant now = Instant.parse("2026-06-05T01:00:00Z");
    private final WorkflowActivationExecutor executor = new WorkflowActivationExecutor(new WorkflowRuntimeActivationService(),
            new WorkflowNodeInstanceStateService(), new WorkflowRouteInstanceStateService(), new WorkflowRouteRuntimeService());

    @Test
    void anyConvergenceDuringInitialActivationDoesNotCreateTasksForDroppedSibling() {
        var nodes = List.of(node("start", WorkflowNodeType.START), branch("split", "join"),
                node("approval", WorkflowNodeType.APPROVAL), converge("join", WorkflowConvergeMode.ANY), node("end", WorkflowNodeType.END));
        var links = List.of(link("start-split", "start", "split"), link("fast", "split", "join"),
                link("slow", "split", "approval"), link("approval-join", "approval", "join"), link("join-end", "join", "end"));
        var graph = WorkflowRuntimeGraph.of(nodes, links);
        var snapshot = snapshot(nodes, links);
        var activation = executor.execute(WorkflowActivationRequest.from(graph, "start"), snapshot.nodes(), snapshot.routes(), "user-1", now);
        assertThat(activation.completed()).isTrue();
        assertThat(activation.blockingApprovalNodeKeys()).isEmpty();
        assertThat(activation.blockingTaskNodeKeys()).isEmpty();
        assertThat(runtimeNode(snapshot, "approval").getNodeStatus()).isEqualTo(WorkflowNodeStatus.SKIPPED);
        var tasks = new WorkflowRuntimeTaskFactory(new WorkflowRuntimeEventFactory(), java.util.Optional.empty(), WorkflowTestSupport.participants())
                .createBlockingTasks(snapshot.instance(), snapshot.nodes(), activation, "user-1", now);
        assertThat(tasks.tasks()).isEmpty();
        assertThat(route(snapshot, "slow").getRouteStatus()).isEqualTo(WorkflowRouteStatus.DROPPED);
    }

    @Test
    void droppedOuterPathKeepsNestedAncestryAndExcludesItsInitialTasks() {
        var nodes = List.of(node("start", WorkflowNodeType.START), branch("outer", "outerJoin"),
                branch("inner", "innerJoin"), node("left", WorkflowNodeType.APPROVAL), node("right", WorkflowNodeType.APPROVAL),
                converge("innerJoin", WorkflowConvergeMode.ALL), converge("outerJoin", WorkflowConvergeMode.ANY), node("end", WorkflowNodeType.END));
        var links = List.of(link("start-outer", "start", "outer"), link("fast", "outer", "outerJoin"),
                link("slow", "outer", "inner"), link("inner-left", "inner", "left"), link("inner-right", "inner", "right"),
                link("left-join", "left", "innerJoin"), link("right-join", "right", "innerJoin"),
                link("innerJoin-outerJoin", "innerJoin", "outerJoin"), link("outerJoin-end", "outerJoin", "end"));
        var snapshot = snapshot(nodes, links);
        var graph = WorkflowRuntimeGraph.of(nodes, links);
        var activation = executor.execute(WorkflowActivationRequest.from(graph, "start"), snapshot.nodes(), snapshot.routes(), "user-1", now);
        assertThat(activation.completed()).isTrue();
        assertThat(activation.blockingApprovalNodeKeys()).isEmpty();
        assertThat(route(snapshot, "inner-left").getParentRouteId()).isEqualTo(route(snapshot, "slow").getId());
        assertThat(route(snapshot, "inner-right").getParentRouteId()).isEqualTo(route(snapshot, "slow").getId());
        assertThat(runtimeNode(snapshot, "left").getNodeStatus()).isEqualTo(WorkflowNodeStatus.SKIPPED);
        assertThat(runtimeNode(snapshot, "right").getNodeStatus()).isEqualTo(WorkflowNodeStatus.SKIPPED);
    }

    @Test
    void nestedAllConvergenceWaitsForEveryEffectivePathAndReturnsToParentScope() {
        var nodes = List.of(node("start", WorkflowNodeType.START), branch("outer", "outerJoin"),
                branch("inner", "innerJoin"), node("innerLeft", WorkflowNodeType.APPROVAL), node("innerRight", WorkflowNodeType.APPROVAL),
                node("outerRight", WorkflowNodeType.APPROVAL), converge("innerJoin", WorkflowConvergeMode.ALL),
                converge("outerJoin", WorkflowConvergeMode.ALL), node("end", WorkflowNodeType.END));
        var links = List.of(link("start-outer", "start", "outer"), link("outer-left", "outer", "inner"),
                link("outer-right", "outer", "outerRight"), link("inner-left", "inner", "innerLeft"),
                link("inner-right", "inner", "innerRight"), link("left-innerJoin", "innerLeft", "innerJoin"),
                link("right-innerJoin", "innerRight", "innerJoin"), link("innerJoin-outerJoin", "innerJoin", "outerJoin"),
                link("outerRight-outerJoin", "outerRight", "outerJoin"), link("outerJoin-end", "outerJoin", "end"));
        var graph = new WorkflowDesignCompiler(new WorkflowConditionService(WorkflowTestSupport.facts()))
                .validate(new WorkflowDesignDocument(nodes, links, null), true, false);
        var snapshot = snapshot(nodes, links);
        executor.execute(WorkflowActivationRequest.from(graph, "start"), snapshot.nodes(), snapshot.routes(), "user-1", now);
        assertThat(route(snapshot, "inner-left").getParentRouteId()).isEqualTo(route(snapshot, "outer-left").getId());
        assertThat(complete(graph, snapshot, "outerRight-outerJoin").completed()).isFalse();
        assertThat(complete(graph, snapshot, "left-innerJoin").completed()).isFalse();
        var finished = complete(graph, snapshot, "right-innerJoin");
        assertThat(finished.completed()).isTrue();
        assertThat(finished.waitingConvergeNodeKeys()).isEmpty();
        assertThat(runtimeNode(snapshot, "innerJoin").getNodeStatus()).isEqualTo(WorkflowNodeStatus.COMPLETED);
        assertThat(runtimeNode(snapshot, "outerJoin").getNodeStatus()).isEqualTo(WorkflowNodeStatus.COMPLETED);
        assertThat(route(snapshot, "innerJoin-outerJoin").getPathRouteId()).isEqualTo(route(snapshot, "outer-left").getId());
        assertThat(route(snapshot, "outer-left").getRouteStatus()).isEqualTo(WorkflowRouteStatus.CLOSED);
        assertThat(route(snapshot, "outer-right").getRouteStatus()).isEqualTo(WorkflowRouteStatus.CLOSED);
    }

    @Test
    void autoConditionAuditRetainsActualActivationDecisionWhenTimeAdvancesBeforeProjection() {
        Instant decisionTime = Instant.parse("2000-01-01T23:59:59Z");
        var clock = org.mockito.Mockito.mock(Clock.class);
        org.mockito.Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        org.mockito.Mockito.when(clock.instant()).thenReturn(decisionTime);
        var delayedActivation = new WorkflowRuntimeActivationService(clock) {
            @Override public WorkflowActivationResult activate(WorkflowActivationRequest request) {
                var result = super.activate(request);
                // Simulate passing midnight between traversal and its audit projection, without waiting in real time.
                org.mockito.Mockito.when(clock.instant()).thenReturn(decisionTime.plusSeconds(2));
                return result;
            }
        };
        var nodes = List.of(node("start", WorkflowNodeType.START), branch("split", "join"),
                node("selected", WorkflowNodeType.APPROVAL), node("rejected", WorkflowNodeType.APPROVAL),
                converge("join", WorkflowConvergeMode.ALL), node("end", WorkflowNodeType.END));
        var selected = link("selected-route", "split", "selected");
        selected.setConditionExpression("NOW() == '2000-01-01T23:59:59Z'");
        var rejected = link("rejected-route", "split", "rejected");
        rejected.setConditionExpression("NOW() != '2000-01-01T23:59:59Z'");
        var links = List.of(link("start-split", "start", "split"), selected, rejected,
                link("selected-join", "selected", "join"), link("rejected-join", "rejected", "join"), link("join-end", "join", "end"));
        var snapshot = snapshot(nodes, links);
        var result = new WorkflowActivationExecutor(delayedActivation, new WorkflowNodeInstanceStateService(),
                new WorkflowRouteInstanceStateService(), new WorkflowRouteRuntimeService()).execute(
                WorkflowActivationRequest.from(WorkflowRuntimeGraph.of(nodes, links), "start"),
                snapshot.nodes(), snapshot.routes(), "user-1", decisionTime);

        var laterFormula = new net.ximatai.muyun.spring.common.formula.FormulaEngine(clock);
        assertThat(laterFormula.evaluateValue(selected.getConditionExpression(),
                net.ximatai.muyun.spring.common.formula.FormulaRuntimeData.of(Map.of()))).isEqualTo(false);
        assertThat(result.blockingApprovalNodeKeys()).containsExactly("selected");
        assertThat(route(snapshot, "selected-route").getRouteStatus()).isEqualTo(WorkflowRouteStatus.EFFECTIVE);
        assertThat(route(snapshot, "selected-route").getConditionMatched()).isTrue();
        assertThat(route(snapshot, "selected-route").getRouteReason()).isEqualTo(WorkflowRouteReason.CONDITION_MATCHED);
        assertThat(route(snapshot, "rejected-route").getRouteStatus()).isEqualTo(WorkflowRouteStatus.INEFFECTIVE);
        assertThat(route(snapshot, "rejected-route").getConditionMatched()).isFalse();
    }

    private WorkflowActivationResult complete(WorkflowRuntimeGraph graph, WorkflowInstanceSnapshot snapshot, String key) {
        var edge = route(snapshot, key);
        runtimeNode(snapshot, edge.getSourceNodeKey()).setNodeStatus(WorkflowNodeStatus.COMPLETED);
        return executor.execute(new WorkflowActivationRequest(graph,
                List.of(new WorkflowActivationTarget(edge.getTargetNodeKey(), edge.getId())), Map.of(), Set.of(), 512),
                snapshot.nodes(), snapshot.routes(), "user-1", now);
    }
    private WorkflowInstanceSnapshot snapshot(List<WorkflowNodeDefinition> nodes, List<WorkflowLinkDefinition> links) {
        var definition = new WorkflowDefinition(); definition.setId("definition-1"); definition.setModuleAlias("sales.contract"); definition.setApprovalEnabled(false);
        var version = new WorkflowVersion(); version.setId("version-1"); version.setVersionNo(1); version.setSnapshotText("{}");
        return new WorkflowInstanceSnapshotFactory(new WorkflowInstanceStateService(), new WorkflowRuntimeEventFactory())
                .build(definition, version, nodes, links, "record-1", "user-1", now);
    }
    private WorkflowNodeInstance runtimeNode(WorkflowInstanceSnapshot snapshot, String key) {
        return snapshot.nodes().stream().filter(node -> key.equals(node.getNodeKey())).findFirst().orElseThrow();
    }
    private WorkflowRouteInstance route(WorkflowInstanceSnapshot snapshot, String key) {
        return snapshot.routes().stream().filter(edge -> key.equals(edge.getRouteKey())).findFirst().orElseThrow();
    }
    private WorkflowNodeDefinition node(String key, WorkflowNodeType type) {
        var node = new WorkflowNodeDefinition(); node.setNodeKey(key); node.setNodeType(type);
        node.setParticipantPolicyText("user:user-1"); return node;
    }
    private WorkflowNodeDefinition branch(String key, String join) {
        var node = node(key, WorkflowNodeType.BRANCH); node.setConvergeNodeKey(join); node.setRouteMode(WorkflowRouteMode.AUTO); return node;
    }
    private WorkflowNodeDefinition converge(String key, WorkflowConvergeMode mode) {
        var node = node(key, WorkflowNodeType.CONVERGE); node.setConvergeMode(mode); return node;
    }
    private WorkflowLinkDefinition link(String key, String source, String target) {
        var edge = new WorkflowLinkDefinition(); edge.setRouteKey(key); edge.setSourceNodeKey(source);
        edge.setTargetNodeKey(target);
        if (Set.of("split", "outer", "inner").contains(source)) edge.setConditionExpression("true");
        return edge;
    }
}
