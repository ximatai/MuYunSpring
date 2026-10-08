package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.ability.ApprovalAbility;
import net.ximatai.muyun.spring.ability.ApprovalState;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DynamicWorkflowApprovalSummaryWriterTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final DynamicRecordService records = mock(DynamicRecordService.class);

    @Test void dynamicDispatchUsesNarrowCommandAndInstanceTenantWhileKeepingAuthorizedAction() {
        var writer = new DynamicWorkflowApprovalSummaryWriter(records);
        var policy = new ActionExecutionPolicy("approve", PlatformActionLevel.RECORD,
                ActionAccessMode.AUTH_REQUIRED, true, true, ActionDefaultGrantPolicy.NONE, null);
        when(records.mainEntityAlias("sales.contract")).thenReturn("contract");
        when(records.writeApprovalState(eq("sales.contract"), eq("contract"), eq("record"), eq(policy), any()))
                .thenAnswer(invocation -> {
                    assertThat(TenantContext.currentTenantId()).contains("instance-tenant");
                    assertThat(TenantContext.isSystem()).isFalse();
                    return 1;
                });
        var summary = summary();
        try (var tenant = TenantContext.use("outer-tenant");
             var action = ActionExecutionContextHolder.use(ActionExecutionContext.ofPolicy(
                     "sales.contract", policy, Set.of("record"), java.util.Optional.empty()))) {
            writer.writeSubmitted(summary);
            assertThat(TenantContext.currentTenantId()).contains("outer-tenant");
            verify(records).writeApprovalState("sales.contract", "contract", "record", policy,
                    new ApprovalState("instance", "processing", "submitter", Instant.EPOCH, null));
        }
        verify(records, never()).updateSystem(anyString(), anyString(), any(), anyString());
    }

    @Test void staticModuleUsesApprovalAbilityAndClearsInSameTenantScope() {
        @SuppressWarnings("unchecked") ApprovalAbility<?> ability = mock(ApprovalAbility.class);
        @SuppressWarnings("unchecked") ObjectProvider<CrudAbility<?>> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation -> Stream.of(ability));
        when(ability.getModuleAlias()).thenReturn("sales.contract");
        when(ability.supportsApproval()).thenReturn(true);
        when(ability.writeApprovalState(eq("record"), any(), any())).thenAnswer(invocation -> {
            assertThat(TenantContext.currentTenantId()).contains("instance-tenant");
            return 1;
        });
        var writer = new DynamicWorkflowApprovalSummaryWriter(records, provider);
        writer.writeSubmitted(summary());
        writer.clearCurrent("instance-tenant", "sales.contract", "record");
        verify(ability).writeApprovalState("record", PlatformAction.UPDATE.executionPolicy(), ApprovalState.empty());
        verifyNoInteractions(records);
        assertThat(TenantContext.hasContext()).isFalse();
    }

    @Test void missingRecordAndUnsupportedStaticModuleFailInsteadOfSilentlyDroppingSummary() {
        when(records.mainEntityAlias("sales.contract")).thenReturn("contract");
        var writer = new DynamicWorkflowApprovalSummaryWriter(records);
        assertThatThrownBy(() -> writer.writeSubmitted(summary())).hasMessageContaining("business record not found");
        @SuppressWarnings("unchecked") CrudAbility<?> ability = mock(CrudAbility.class);
        @SuppressWarnings("unchecked") ObjectProvider<CrudAbility<?>> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation -> Stream.of(ability));
        when(ability.getModuleAlias()).thenReturn("sales.contract");
        assertThatThrownBy(() -> new DynamicWorkflowApprovalSummaryWriter(records, provider).writeSubmitted(summary()))
                .hasMessageContaining("static module does not support approval");
        assertThat(TenantContext.hasContext()).isFalse();
    }

    private WorkflowApprovalSummary summary() {
        return new WorkflowApprovalSummary("instance-tenant", "sales.contract", "record", "instance",
                WorkflowApprovalStatus.PROCESSING, "submitter", Instant.EPOCH, null);
    }
}
