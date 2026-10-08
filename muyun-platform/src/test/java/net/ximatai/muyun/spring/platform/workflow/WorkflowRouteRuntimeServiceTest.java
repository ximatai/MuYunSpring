package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowRouteRuntimeServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowRouteRuntimeService service = new WorkflowRouteRuntimeService();

    @Test
    void shouldPersistSelectedReasonOnlyForManualSelectedRoute() {
        WorkflowRouteInstance manual = route("manual", WorkflowRouteStatus.CANDIDATE);
        WorkflowRouteInstance defaultRoute = route("default", WorkflowRouteStatus.CANDIDATE);

        service.effectiveRoute(manual, WorkflowRouteReason.MANUAL_SELECTED, "user-1",
                Instant.parse("2026-06-05T01:00:00Z"), "choose manual route");
        service.effectiveRoute(defaultRoute, WorkflowRouteReason.DEFAULT_SELECTED, "user-1",
                Instant.parse("2026-06-05T01:00:00Z"), "should be ignored");

        assertThat(manual.getSelectedReason()).isEqualTo("choose manual route");
        assertThat(defaultRoute.getSelectedReason()).isNull();
    }

    @Test
    void shouldCloseArrivedRouteAndDropPendingRoutesWhenAnyConvergePasses() {
        WorkflowRouteInstance first = route("r1", WorkflowRouteStatus.EFFECTIVE);
        WorkflowRouteInstance second = route("r2", WorkflowRouteStatus.EFFECTIVE);

        WorkflowConvergeDecision decision = service.handleConvergeArrival(first, List.of(first, second),
                WorkflowConvergeMode.ANY, null, Instant.parse("2026-06-05T01:00:00Z"));

        assertThat(decision.passed()).isTrue();
        assertThat(decision.routesToClose()).containsExactly(first);
        assertThat(decision.routesToDrop()).containsExactly(second);
        assertThat(first.getRouteStatus()).isEqualTo(WorkflowRouteStatus.CLOSED);
        assertThat(second.getRouteStatus()).isEqualTo(WorkflowRouteStatus.DROPPED);
    }

    @Test
    void shouldTreatArrivedRouteAsInScopeWhenSiblingListDoesNotContainIt() {
        WorkflowRouteInstance first = route("r1", WorkflowRouteStatus.EFFECTIVE);
        WorkflowRouteInstance second = route("r2", WorkflowRouteStatus.EFFECTIVE);

        WorkflowConvergeDecision decision = service.handleConvergeArrival(first, List.of(second),
                WorkflowConvergeMode.ANY, null, Instant.parse("2026-06-05T01:00:00Z"));

        assertThat(decision.passed()).isTrue();
        assertThat(decision.routesToClose()).containsExactly(first);
        assertThat(decision.routesToDrop()).containsExactly(second);
    }

    @Test
    void shouldWaitUntilAllEffectiveRoutesArriveForAllConverge() {
        WorkflowRouteInstance first = route("r1", WorkflowRouteStatus.EFFECTIVE);
        WorkflowRouteInstance second = route("r2", WorkflowRouteStatus.EFFECTIVE);

        WorkflowConvergeDecision firstDecision = service.handleConvergeArrival(first, List.of(first, second),
                WorkflowConvergeMode.ALL, null, Instant.parse("2026-06-05T01:00:00Z"));

        assertThat(firstDecision.passed()).isFalse();
        assertThat(first.getRouteStatus()).isEqualTo(WorkflowRouteStatus.EFFECTIVE);

        WorkflowConvergeDecision secondDecision = service.handleConvergeArrival(second, List.of(first, second),
                WorkflowConvergeMode.ALL, null, Instant.parse("2026-06-05T02:00:00Z"));

        assertThat(secondDecision.passed()).isTrue();
        assertThat(secondDecision.routesToClose()).containsExactly(first, second);
        assertThat(first.getRouteStatus()).isEqualTo(WorkflowRouteStatus.CLOSED);
        assertThat(second.getRouteStatus()).isEqualTo(WorkflowRouteStatus.CLOSED);
    }

    @Test
    void shouldPassRatioConvergeWhenArrivedPercentReachesThreshold() {
        WorkflowRouteInstance first = route("r1", WorkflowRouteStatus.EFFECTIVE);
        WorkflowRouteInstance second = route("r2", WorkflowRouteStatus.EFFECTIVE);
        WorkflowRouteInstance third = route("r3", WorkflowRouteStatus.EFFECTIVE);

        service.handleConvergeArrival(first, List.of(first, second, third),
                WorkflowConvergeMode.RATIO, 60, Instant.parse("2026-06-05T01:00:00Z"));
        WorkflowConvergeDecision decision = service.handleConvergeArrival(second, List.of(first, second, third),
                WorkflowConvergeMode.RATIO, 60, Instant.parse("2026-06-05T02:00:00Z"));

        assertThat(decision.passed()).isTrue();
        assertThat(decision.routesToClose()).containsExactly(first, second);
        assertThat(decision.routesToDrop()).containsExactly(third);
    }

    @Test
    void shouldRejectInvalidConvergeRatio() {
        WorkflowRouteInstance first = route("r1", WorkflowRouteStatus.EFFECTIVE);

        assertThatThrownBy(() -> service.handleConvergeArrival(first, List.of(first),
                WorkflowConvergeMode.RATIO, 0, Instant.now()))
                .isInstanceOf(PlatformException.class);
    }

    private WorkflowRouteInstance route(String id, WorkflowRouteStatus status) {
        WorkflowRouteInstance route = new WorkflowRouteInstance();
        route.setId(id);
        route.setInstanceId("instance-1");
        route.setRouteKey(id);
        route.setRouteRunId(id + "-run");
        route.setSourceNodeKey("branch");
        route.setTargetNodeKey("task-" + id);
        route.setBranchNodeKey("branch");
        route.setBranchRunId("branch-run-1");
        route.setConvergeNodeKey("join");
        route.setConvergeRunId("join-run-1");
        route.setParentRouteId("parent");
        route.setRouteStatus(status);
        return route;
    }
}
