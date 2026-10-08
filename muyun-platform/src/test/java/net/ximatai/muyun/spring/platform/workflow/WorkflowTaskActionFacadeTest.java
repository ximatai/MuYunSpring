package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowTaskActionFacadeTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowTaskActionService actionService = mock(WorkflowTaskActionService.class);
    private final WorkflowTaskActionAvailabilityService availabilityService =
            mock(WorkflowTaskActionAvailabilityService.class);
    private final WorkflowTaskActionFacade facade = new WorkflowTaskActionFacade(actionService, availabilityService);

    @Test
    void shouldExposeAvailableActions() {
        List<WorkflowTaskAvailableAction> actions = List.of(WorkflowTaskAvailableAction.of("approve", "通过"));
        when(availabilityService.availableActions("task-1", "user-1")).thenReturn(actions);

        assertThat(facade.availableActions("task-1", "user-1")).isSameAs(actions);
    }

    @Test
    void shouldDispatchTaskActionsByCode() {
        WorkflowTaskActionRequest request = WorkflowTaskActionRequest.complete("task-1", "user-1", "ok");

        facade.execute("approve", request);
        facade.execute("reject", request);
        facade.execute("rollback", request);
        facade.execute("resubmit", request);
        facade.execute("complete", request);
        facade.execute("notice", request);
        facade.execute("read", request);
        facade.execute("transfer", request);
        facade.execute("addSign", request);
        facade.execute("invalidate", request);
        facade.execute("cancel", request);

        verify(actionService).approve(request);
        verify(actionService).reject(request);
        verify(actionService).rollback(request);
        verify(actionService).resubmit(request);
        verify(actionService).completeBusinessTask(request);
        verify(actionService).notice(request);
        verify(actionService).readNotice(request);
        verify(actionService).transfer(request);
        verify(actionService).addSign(request);
        verify(actionService).invalidate(request);
        verify(actionService).cancel(request);
    }

    @Test
    void shouldRejectUnsupportedActionCode() {
        WorkflowTaskActionRequest request = WorkflowTaskActionRequest.complete("task-1", "user-1", "ok");

        assertThatThrownBy(() -> facade.execute("unknown", request))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("unsupported workflow task action");
        assertThatThrownBy(() -> facade.execute(" ", request))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("action code");
    }
}
