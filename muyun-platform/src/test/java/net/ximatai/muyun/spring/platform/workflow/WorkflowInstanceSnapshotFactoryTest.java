package net.ximatai.muyun.spring.platform.workflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowInstanceSnapshotFactoryTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowRuntimeEventFactory eventFactory = new WorkflowRuntimeEventFactory();
    private final WorkflowInstanceSnapshotFactory factory = new WorkflowInstanceSnapshotFactory(
            new WorkflowInstanceStateService(), eventFactory);

    @Test
    void shouldCreateInstanceNodeRouteAndStartEventSnapshot() {
        WorkflowInstanceSnapshot snapshot = factory.build(definition(), version(),
                List.of(node("start", WorkflowNodeType.START), node("approve", WorkflowNodeType.APPROVAL)),
                List.of(link("r1", "start", "approve")),
                "record-1", "user-1", Instant.parse("2026-06-05T01:00:00Z"));

        assertThat(snapshot.instance().getId()).isNotBlank();
        assertThat(snapshot.instance().getApprovalStatus()).isEqualTo(WorkflowApprovalStatus.PROCESSING);
        assertThat(snapshot.instance().getSemanticJson()).isEqualTo("{\"nodes\":[\"start\"]}");
        assertThat(snapshot.instance().getLayoutJson()).isEqualTo("{\"zoom\":1}");
        assertThat(snapshot.nodes()).hasSize(2)
                .allSatisfy(node -> {
                    assertThat(node.getInstanceId()).isEqualTo(snapshot.instance().getId());
                    assertThat(node.getNodeStatus()).isEqualTo(WorkflowNodeStatus.WAITING);
                    assertThat(node.getNodeRunId()).endsWith(":1");
                });
        assertThat(snapshot.nodes()).filteredOn(node -> "approve".equals(node.getNodeKey()))
                .first()
                .satisfies(node -> {
                    assertThat(node.getAllowReject()).isTrue();
                    assertThat(node.getRequireRejectReason()).isTrue();
                    assertThat(node.getAllowRejectReturnToMe()).isTrue();
                    assertThat(node.getAllowRollback()).isFalse();
                    assertThat(node.getRequireRollbackReason()).isTrue();
                    assertThat(node.getAllowTerminate()).isTrue();
                    assertThat(node.getRequireTerminateReason()).isTrue();
                    assertThat(node.getAllowAddSign()).isTrue();
                    assertThat(node.getTaskDefinitionId()).isEqualTo("task-def-1");
                });
        assertThat(snapshot.nodes()).filteredOn(node -> "branch".equals(node.getNodeKey()))
                .isEmpty();
        assertThat(snapshot.routes()).hasSize(1)
                .first()
                .satisfies(route -> {
                    assertThat(route.getInstanceId()).isEqualTo(snapshot.instance().getId());
                    assertThat(route.getRouteStatus()).isEqualTo(WorkflowRouteStatus.CANDIDATE);
                    assertThat(route.getRouteRunId()).isEqualTo("r1:1");
                });
        assertThat(snapshot.events()).hasSize(1)
                .first()
                .satisfies(event -> {
                    assertThat(event.getEventType()).isEqualTo(WorkflowEventType.INSTANCE_STARTED);
                    assertThat(event.getOperatorId()).isEqualTo("user-1");
                });
    }

    @Test
    void shouldCopyManualBranchGovernanceToNodeSnapshot() {
        WorkflowNodeDefinition branch = node("branch", WorkflowNodeType.BRANCH);
        branch.setRouteMode(WorkflowRouteMode.MANUAL);
        branch.setSelectorNodeKey("start");
        branch.setRequireManualSelectionReason(true);

        WorkflowInstanceSnapshot snapshot = factory.build(definition(), version(),
                List.of(node("start", WorkflowNodeType.START), branch, node("end", WorkflowNodeType.END)),
                List.of(link("toBranch", "start", "branch"), link("toEnd", "branch", "end")),
                "record-1", "user-1", Instant.parse("2026-06-05T01:00:00Z"));

        assertThat(snapshot.nodes()).filteredOn(node -> "branch".equals(node.getNodeKey()))
                .first()
                .satisfies(node -> {
                    assertThat(node.getRouteMode()).isEqualTo(WorkflowRouteMode.MANUAL);
                    assertThat(node.getSelectorNodeKey()).isEqualTo("start");
                    assertThat(node.getRequireManualSelectionReason()).isTrue();
                });
    }

    private WorkflowDefinition definition() {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId("definition-1");
        definition.setTenantId("tenant-1");
        definition.setModuleAlias("crm.customer");
        definition.setApprovalEnabled(Boolean.TRUE);
        return definition;
    }

    private WorkflowVersion version() {
        WorkflowVersion version = new WorkflowVersion();
        version.setId("version-1");
        version.setVersionNo(1);
        version.setSnapshotText("{\"nodes\":[]}");
        version.setSemanticJson("{\"nodes\":[\"start\"]}");
        version.setLayoutJson("{\"zoom\":1}");
        return version;
    }

    private WorkflowNodeDefinition node(String key, WorkflowNodeType type) {
        WorkflowNodeDefinition node = new WorkflowNodeDefinition();
        node.setNodeKey(key);
        node.setNodeType(type);
        if ("approve".equals(key)) {
            node.setAllowReject(Boolean.TRUE);
            node.setRequireRejectReason(Boolean.TRUE);
            node.setAllowRejectReturnToMe(Boolean.TRUE);
            node.setAllowRollback(Boolean.FALSE);
            node.setRequireRollbackReason(Boolean.TRUE);
            node.setAllowTerminate(Boolean.TRUE);
            node.setRequireTerminateReason(Boolean.TRUE);
            node.setAllowAddSign(Boolean.TRUE);
            node.setTaskDefinitionId("task-def-1");
        }
        return node;
    }

    private WorkflowLinkDefinition link(String key, String source, String target) {
        WorkflowLinkDefinition link = new WorkflowLinkDefinition();
        link.setRouteKey(key);
        link.setSourceNodeKey(source);
        link.setTargetNodeKey(target);
        return link;
    }
}
