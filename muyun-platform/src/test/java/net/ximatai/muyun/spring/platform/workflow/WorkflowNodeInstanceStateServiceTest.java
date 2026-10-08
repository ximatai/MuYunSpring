package net.ximatai.muyun.spring.platform.workflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowNodeInstanceStateServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowNodeInstanceStateService service = new WorkflowNodeInstanceStateService();

    @Test
    void shouldMarkActivatedControlNodeCompletedAndBlockingNodeActive() {
        WorkflowNodeInstance start = node("start");
        WorkflowNodeInstance approve = node("approve");
        WorkflowActivationResult activation = new WorkflowActivationResult(
                List.of("start", "approve"),
                List.of("r1"),
                List.of("approve"),
                List.of(),
                List.of(),
                List.of(),
                false
        );

        service.applyActivation(List.of(start, approve), activation, Instant.parse("2026-06-05T01:00:00Z"));

        assertThat(start.getNodeStatus()).isEqualTo(WorkflowNodeStatus.COMPLETED);
        assertThat(start.getCompletedAt()).isEqualTo(Instant.parse("2026-06-05T01:00:00Z"));
        assertThat(approve.getNodeStatus()).isEqualTo(WorkflowNodeStatus.ACTIVE);
        assertThat(approve.getCompletedAt()).isNull();
    }

    private WorkflowNodeInstance node(String nodeKey) {
        WorkflowNodeInstance node = new WorkflowNodeInstance();
        node.setNodeKey(nodeKey);
        node.setNodeStatus(WorkflowNodeStatus.WAITING);
        return node;
    }
}
