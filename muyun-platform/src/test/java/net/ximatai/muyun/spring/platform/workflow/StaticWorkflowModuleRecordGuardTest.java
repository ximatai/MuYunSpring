package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.model.standard.StandardDataScopedEntity;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class StaticWorkflowModuleRecordGuardTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    @Test
    void shouldPassWhenStaticCrudAbilityCanSelectRecord() {
        CrudAbility<?> ability = mock(CrudAbility.class);
        EntityContract record = mock(EntityContract.class);
        when(ability.getModuleAlias()).thenReturn("sales.contract");
        when(ability.select("record-1")).thenReturn(record);
        StaticWorkflowModuleRecordGuard guard = new StaticWorkflowModuleRecordGuard(List.of(ability), WorkflowTestSupport.submissionPolicies(),
                new net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService());

        guard.beforeSubmit(WorkflowSubmitRequest.approval("sales.contract", "record-1"));

        verify(ability).select("record-1");
    }

    @Test
    void shouldRequireStaticDataScopeBeforeSelectingRecord() {
        @SuppressWarnings("unchecked")
        DataScopeAbility<DataScopedRecord> ability = mock(DataScopeAbility.class);
        DataScopedRecord record = new DataScopedRecord();
        DataScopeCriteriaResult scope = DataScopeCriteriaResult.unrestricted(Criteria.of());
        when(ability.getModuleAlias()).thenReturn("sales.contract");
        when(ability.requireRecordScopeResult(any(ActionExecutionPolicy.class), eq(Set.of("record-1"))))
                .thenReturn(scope);
        when(ability.select("record-1")).thenReturn(record);
        when(ability.withDataScopeTenant(eq(scope), any())).thenAnswer(invocation -> {
            Supplier<?> supplier = invocation.getArgument(1);
            return supplier.get();
        });
        StaticWorkflowModuleRecordGuard guard = new StaticWorkflowModuleRecordGuard(List.of(ability), WorkflowTestSupport.submissionPolicies(),
                new net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService());

        guard.beforeSubmit(WorkflowSubmitRequest.approval("sales.contract", "record-1"));

        verify(ability).requireRecordScopeResult(any(ActionExecutionPolicy.class), eq(Set.of("record-1")));
        verify(ability).withDataScopeTenant(eq(scope), any());
    }

    @Test
    void shouldUseCurrentActionPolicyWhenSubmittingFromWebActionEndpoint() {
        @SuppressWarnings("unchecked")
        DataScopeAbility<DataScopedRecord> ability = mock(DataScopeAbility.class);
        DataScopedRecord record = new DataScopedRecord();
        DataScopeCriteriaResult scope = DataScopeCriteriaResult.unrestricted(Criteria.of());
        ActionExecutionPolicy policy = new ActionExecutionPolicy("submitApproval", PlatformActionLevel.RECORD,
                null, true, true, null, null);
        when(ability.getModuleAlias()).thenReturn("sales.contract");
        when(ability.requireRecordScopeResult(eq(policy), eq(Set.of("record-1")))).thenReturn(scope);
        when(ability.select("record-1")).thenReturn(record);
        when(ability.withDataScopeTenant(eq(scope), any())).thenAnswer(invocation -> {
            Supplier<?> supplier = invocation.getArgument(1);
            return supplier.get();
        });
        StaticWorkflowModuleRecordGuard guard = new StaticWorkflowModuleRecordGuard(List.of(ability), WorkflowTestSupport.submissionPolicies(),
                new net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService());

        try (ActionExecutionContextHolder.Scope ignored = ActionExecutionContextHolder.use(
                ActionExecutionContext.ofPolicy("sales.contract", policy, Set.of("record-1"), java.util.Optional.empty()))) {
            guard.beforeSubmit(WorkflowSubmitRequest.approval("sales.contract", "record-1"));
        }

        verify(ability).requireRecordScopeResult(eq(policy), eq(Set.of("record-1")));
    }

    @Test
    void shouldRejectMissingStaticRecord() {
        CrudAbility<?> ability = mock(CrudAbility.class);
        when(ability.getModuleAlias()).thenReturn("sales.contract");
        when(ability.select("missing")).thenReturn(null);
        StaticWorkflowModuleRecordGuard guard = new StaticWorkflowModuleRecordGuard(List.of(ability), WorkflowTestSupport.submissionPolicies(),
                new net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService());

        assertThatThrownBy(() -> guard.beforeSubmit(WorkflowSubmitRequest.approval("sales.contract", "missing")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("static record not found");
    }

    @Test
    void shouldSkipWhenNoStaticAbilityMatchesModuleAlias() {
        CrudAbility<?> ability = mock(CrudAbility.class);
        when(ability.getModuleAlias()).thenReturn("sales.contract");
        StaticWorkflowModuleRecordGuard guard = new StaticWorkflowModuleRecordGuard(List.of(ability), WorkflowTestSupport.submissionPolicies(),
                new net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService());

        guard.beforeSubmit(WorkflowSubmitRequest.approval("service.ticket", "record-1"));

        verify(ability).getModuleAlias();
        verifyNoMoreInteractions(ability);
    }

    @Test
    void shouldSkipWhenNoStaticAbilitiesAreRegistered() {
        StaticWorkflowModuleRecordGuard guard = new StaticWorkflowModuleRecordGuard(List.of(), WorkflowTestSupport.submissionPolicies(),
                new net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService());
        CrudAbility<?> ability = mock(CrudAbility.class);

        guard.beforeSubmit(WorkflowSubmitRequest.approval("sales.contract", "record-1"));

        verifyNoInteractions(ability);
    }

    private static class DataScopedRecord extends StandardDataScopedEntity {
    }
}
