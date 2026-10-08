package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;

class WorkflowActionPolicyServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final ActionExecutionPolicyService executionPolicyService = mock(ActionExecutionPolicyService.class);
    private final WorkflowModuleRecordGuard recordGuard = mock(WorkflowModuleRecordGuard.class);
    private final WorkflowActionPolicyService service = new WorkflowActionPolicyService(
            executionPolicyService, List.of(recordGuard));

    @Test
    void shouldRequireRuntimeActionThroughUnifiedActionPolicy() {
        WorkflowInstance instance = instance();

        service.requireRuntimeAction(instance, "approve");

        ArgumentCaptor<ActionExecutionContext> context = ArgumentCaptor.forClass(ActionExecutionContext.class);
        verify(executionPolicyService).requireRecordAction(context.capture());
        assertThat(context.getValue().moduleAlias()).isEqualTo("sales.contract");
        assertThat(context.getValue().actionCode()).isEqualTo("approve");
        assertThat(context.getValue().recordIds()).containsExactly("record-1");
        assertThat(context.getValue().actionPolicy().level()).isEqualTo(PlatformActionLevel.RECORD);
        assertThat(context.getValue().actionPolicy().accessMode()).isEqualTo(ActionAccessMode.AUTH_REQUIRED);
        assertThat(context.getValue().actionPolicy().actionAuth()).isTrue();
        assertThat(context.getValue().actionPolicy().dataAuth()).isTrue();
        assertThat(context.getValue().actionPolicy().permissionActionCode()).isEqualTo("approve");
        verify(recordGuard).requireRecordAction("sales.contract", "record-1", context.getValue().actionPolicy());
    }

    @Test
    void shouldPropagateRuntimeActionAuthorizationFailure() {
        doThrow(new PlatformException("workflow action denied")).when(executionPolicyService)
                .requireRecordAction(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> service.requireRuntimeAction(instance(), "approve"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("workflow action denied");
        verifyNoInteractions(recordGuard);
    }

    @Test
    void shouldRequireManagementActionThroughDedicatedManagementModule() {
        service.requireManagementAction(WorkflowActionPolicyService.MANAGEMENT_DELETE_HISTORY_ACTION);

        ArgumentCaptor<ActionExecutionContext> context = ArgumentCaptor.forClass(ActionExecutionContext.class);
        verify(executionPolicyService).requireAuthorized(context.capture());
        assertThat(context.getValue().moduleAlias()).isEqualTo(WorkflowActionPolicyService.MANAGEMENT_MODULE_ALIAS);
        assertThat(context.getValue().actionCode())
                .isEqualTo(WorkflowActionPolicyService.MANAGEMENT_DELETE_HISTORY_ACTION);
        assertThat(context.getValue().recordIds()).isEmpty();
        assertThat(context.getValue().actionPolicy().level()).isEqualTo(PlatformActionLevel.LIST);
        assertThat(context.getValue().actionPolicy().accessMode()).isEqualTo(ActionAccessMode.AUTH_REQUIRED);
        assertThat(context.getValue().actionPolicy().actionAuth()).isTrue();
        assertThat(context.getValue().actionPolicy().dataAuth()).isFalse();
        verifyNoInteractions(recordGuard);
    }

    @Test
    void shouldRequireReasonForForceApproveManagementTaskAction() {
        assertThatThrownBy(() -> service.requireManagementTaskAction(
                WorkflowActionPolicyService.MANAGEMENT_FORCE_APPROVE_ACTION, null))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("workflow action reason is required: forceApprove");

        ArgumentCaptor<ActionExecutionContext> context = ArgumentCaptor.forClass(ActionExecutionContext.class);
        verify(executionPolicyService).requireAuthorized(context.capture());
        assertThat(context.getValue().actionCode())
                .isEqualTo(WorkflowActionPolicyService.MANAGEMENT_FORCE_APPROVE_ACTION);
    }

    private WorkflowInstance instance() {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("instance-1");
        instance.setModuleAlias("sales.contract");
        instance.setRecordId("record-1");
        return instance;
    }
}
