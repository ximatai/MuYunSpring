package net.ximatai.muyun.spring.platform.workflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowTaskActionRequestTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    @Test
    void builderCopiesAllSharedActionFields() {
        Instant operatedAt = Instant.parse("2026-07-20T08:00:00Z");
        WorkflowManualRouteSelection selection = new WorkflowManualRouteSelection(
                "branch-a", "route-a", "preferred");

        WorkflowTaskActionRequest request = WorkflowTaskActionRequest.builder("task-1", "user-1")
                .reason("approved")
                .operatedAt(operatedAt)
                .selectedRoute("route-a", "preferred")
                .manualRouteSelections(List.of(selection))
                .designerSnapshot(" {\"nodes\":[]} ", " {\"cells\":[]} ")
                .build();

        assertThat(request.taskId()).isEqualTo("task-1");
        assertThat(request.operatorId()).isEqualTo("user-1");
        assertThat(request.reason()).isEqualTo("approved");
        assertThat(request.operatedAt()).isEqualTo(operatedAt);
        assertThat(request.selectedRouteKey()).isEqualTo("route-a");
        assertThat(request.selectedReason()).isEqualTo("preferred");
        assertThat(request.manualRouteSelections()).containsExactly(selection);
        assertThat(request.semanticJson()).isEqualTo(" {\"nodes\":[]} ");
        assertThat(request.layoutJson()).isEqualTo(" {\"cells\":[]} ");
    }

    @Test
    void builderRejectsMixedActionSpecificOptions() {
        assertThatThrownBy(() -> WorkflowTaskActionRequest.builder("task-1", "user-1")
                .targetAssigneeId("user-2")
                .addSignSegment(new WorkflowAddSignSegment(List.of(), List.of()))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be combined");
    }
}
