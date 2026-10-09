package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.model.standard.StandardDataScopedEntity;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.module.*;
import net.ximatai.muyun.spring.platform.support.ModuleActionTestServices;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowSubmitAuthorizationContractTest {
    private static final String MODULE = "sales.contract";
    private final TestMemoryDao<PlatformModuleAction> actionDao = new TestMemoryDao<>();
    private final PlatformModuleActionService actions = ModuleActionTestServices.withDeclaredDataPolicy(actionDao,
            new PlatformModuleService(new TestMemoryDao<>(), event -> {}));
    private final WorkflowSubmitActionPolicyResolver policies =
            new WorkflowSubmitActionPolicyResolver(WorkflowTestSupport.provider(actions));
    private final WorkflowSubmitRequest request = WorkflowSubmitRequest.approval(MODULE, "record-1")
            .withOperator("user-1").withOperatedAt(Instant.parse("2026-10-09T01:00:00Z"));

    @BeforeEach void installHost() { WorkflowTestMutationHost.install(); }
    @AfterEach void resetHost() { WorkflowTestMutationHost.reset(); }

    @ParameterizedTest
    @CsvSource({"true, true, false", "false, true, false", "true, false, false", "false, false, false"})
    void previewManualBranchesAndDomainSubmissionUseGovernedPolicyIncludingSummaryWrite(
            boolean dynamic, boolean actionAuth, boolean dataAuth) {
        publishAction(actionAuth, dataAuth);
        var expected = actions.requireExecutionPolicy(MODULE, "submitApproval");
        var guard = guard(dynamic, expected);
        var selector = mock(WorkflowDefinitionSelector.class);
        var runtime = mock(WorkflowRuntimeSubmitService.class);
        var writer = mock(WorkflowApprovalSummaryWriter.class);
        var automatic = mock(WorkflowAutomaticApprovalService.class);
        var definition = new WorkflowDefinition();
        var version = new WorkflowVersion();
        var start = new WorkflowNodeDefinition();
        start.setNodeKey("start"); start.setNodeType(WorkflowNodeType.START);
        var selection = new WorkflowDefinitionSelection(definition, version, List.of(start), List.of());
        when(selector.select(request)).thenReturn(selection);
        var instance = new WorkflowInstance();
        instance.setId("instance-1"); instance.setModuleAlias(MODULE); instance.setRecordId("record-1");
        instance.setApprovalEnabled(true); instance.setApprovalStatus(WorkflowApprovalStatus.PROCESSING);
        var draft = new WorkflowSubmitDraft(instance, List.of(), List.of(), List.of(), List.of(), null);
        when(runtime.preview(definition, version, selection.nodes(), selection.links(), request.recordId(),
                request.operatorId(), request.operatedAt(), null, null, List.of())).thenReturn(draft);
        when(runtime.submit(definition, version, selection.nodes(), selection.links(), request.recordId(),
                request.operatorId(), request.operatedAt(), null, null, List.of())).thenReturn(draft);
        doAnswer(call -> {
            assertThat(ActionExecutionContextHolder.current().orElseThrow().actionPolicy()).isEqualTo(expected);
            return null;
        }).when(writer).writeSubmitted(any());
        var facade = new WorkflowSubmitFacade(selector, runtime, Optional.of(writer), List.of(guard),
                WorkflowTestSupport.provider(automatic), policies);
        var conditions = mock(WorkflowConditionService.class);
        when(conditions.businessFacts(anyList(), eq(MODULE), eq("record-1"))).thenReturn(Map.of());
        when(conditions.manualMatches(anyMap(), anyMap())).thenReturn(Map.of());
        var reads = new WorkflowSubmitReadFacade(mock(WorkflowInstanceDao.class), selector, facade,
                List.of(guard), conditions);

        assertThat(reads.preview(request).instance()).isSameAs(instance);
        assertThat(reads.manualBranches(request)).isEmpty();
        assertThat(facade.submit(request).approvalSummaryWritten()).isTrue();
        verify(writer).writeSubmitted(any());
        assertThat(ActionExecutionContextHolder.current()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void missingAndDisabledSubmissionActionsAreRejectedBeforeBusinessAccess(boolean dynamic) {
        var guard = guard(dynamic, null);
        assertThatThrownBy(() -> guard.beforeSubmit(request)).isInstanceOf(PlatformAccessDeniedException.class);
        var action = publishAction(false, false);
        action.setEnabled(false); actionDao.updateById(action);
        assertThatThrownBy(() -> guard.beforeSubmit(request)).isInstanceOf(PlatformAccessDeniedException.class);
    }

    @ParameterizedTest
    @CsvSource({"sales.other, record-1", "sales.contract, other-record"})
    void unrelatedCallingContextCannotReplaceTheGovernedSubmissionPolicy(String module, String record) {
        publishAction(false, false);
        try (var ignored = ActionExecutionContextHolder.use(ActionExecutionContext.ofPolicy(module,
                WorkflowActionPolicyService.runtimePolicy("resubmit"), Set.of(record), Optional.empty()))) {
            assertThat(policies.resolve(request)).isEqualTo(actions.requireExecutionPolicy(MODULE, "submitApproval"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preserveVerifiedCallingPolicyForResubmissionAndManagement(boolean dynamic) {
        var callingPolicy = WorkflowActionPolicyService.runtimePolicy("resubmit");
        var guard = guard(dynamic, callingPolicy);
        try (var ignored = ActionExecutionContextHolder.use(ActionExecutionContext.ofPolicy(MODULE,
                callingPolicy, Set.of("record-1"), Optional.empty()))) {
            guard.beforeSubmit(request);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void disablingStaticDataScopeDoesNotBypassActionAuthorization(boolean scopedAbility) {
        publishAction(true, false);
        var roleService = mock(net.ximatai.muyun.spring.iam.role.RoleService.class);
        var authorization = new net.ximatai.muyun.spring.iam.role.RoleActionExecutionPolicyService(roleService);
        var ability = scopedAbility
                ? mock(DataScopeAbility.class, CALLS_REAL_METHODS)
                : mock(net.ximatai.muyun.spring.ability.CrudAbility.class);
        when(ability.getModuleAlias()).thenReturn(MODULE);
        var guard = new StaticWorkflowModuleRecordGuard(List.of(ability), policies, authorization);
        var selector = mock(WorkflowDefinitionSelector.class);
        var runtime = mock(WorkflowRuntimeSubmitService.class);
        var writer = mock(WorkflowApprovalSummaryWriter.class);
        var automatic = mock(WorkflowAutomaticApprovalService.class);
        var facade = new WorkflowSubmitFacade(selector, runtime, Optional.of(writer), List.of(guard),
                WorkflowTestSupport.provider(automatic), policies);
        var reads = new WorkflowSubmitReadFacade(mock(WorkflowInstanceDao.class), selector, facade,
                List.of(guard), mock(WorkflowConditionService.class));
        // Keep action authorization required while disabling only data authorization.
        var action = actions.findByModuleAliasAndActionCode(MODULE, "submitApproval");
        action.setDefaultGrantPolicyOverride(ActionDefaultGrantPolicy.NONE);
        actionDao.updateById(action);
        try (var ignored = net.ximatai.muyun.spring.common.identity.CurrentUserContext.use(
                net.ximatai.muyun.spring.common.identity.CurrentUser.tenantUser("user-1", "ordinary", "tenant-1"))) {
            assertThatThrownBy(() -> reads.preview(request)).isInstanceOf(PlatformAccessDeniedException.class);
            assertThatThrownBy(() -> reads.manualBranches(request)).isInstanceOf(PlatformAccessDeniedException.class);
            assertThatThrownBy(() -> facade.submit(request)).isInstanceOf(PlatformAccessDeniedException.class);
        }
        verify(ability, never()).select(anyString());
        verifyNoInteractions(selector, runtime, writer, automatic);
    }

    private PlatformModuleAction publishAction(boolean actionAuth, boolean dataAuth) {
        var action = new PlatformModuleAction();
        action.setModuleAlias(MODULE); action.setActionCode("submitApproval");
        action.setActionLevel(EntityActionLevel.RECORD); action.setEnabled(true);
        action.setActionAuth(true); action.setDataAuth(true);
        action.setAccessModeOverride(actionAuth || dataAuth
                ? EntityActionAccessMode.AUTH_REQUIRED : EntityActionAccessMode.LOGIN_REQUIRED);
        action.setDefaultGrantPolicyOverride(ActionDefaultGrantPolicy.ANY_LOGIN_USER);
        action.setActionAuthOverride(actionAuth); action.setDataAuthOverride(dataAuth);
        actionDao.insert(action);
        return action;
    }

    private WorkflowModuleRecordGuard guard(boolean dynamic, ActionExecutionPolicy expected) {
        if (dynamic) {
            var records = mock(DynamicRecordService.class);
            when(records.mainEntityAlias(MODULE)).thenReturn("contract");
            when(records.selectSystem(MODULE, "contract", "record-1")).thenReturn(mock(DynamicRecord.class));
            doAnswer(call -> {
                assertThat(call.getArgument(2, ActionExecutionPolicy.class)).isEqualTo(expected);
                return null;
            }).when(records).requireRecordActionScope(eq(MODULE), eq("contract"), any(), anySet(), any());
            return new DynamicWorkflowModuleRecordGuard(records, policies);
        }
        @SuppressWarnings("unchecked") var ability = (DataScopeAbility<StandardDataScopedEntity>) mock(DataScopeAbility.class);
        when(ability.getModuleAlias()).thenReturn(MODULE);
        when(ability.requireRecordScopeResult(any(), eq(Set.of("record-1")))).thenAnswer(call -> {
            assertThat(call.getArgument(0, ActionExecutionPolicy.class)).isEqualTo(expected);
            return DataScopeCriteriaResult.unrestricted(Criteria.of());
        });
        when(ability.withDataScopeTenant(any(), any())).thenAnswer(call -> call.getArgument(1, Supplier.class).get());
        when(ability.select("record-1")).thenReturn(new StandardDataScopedEntity() {});
        return new StaticWorkflowModuleRecordGuard(List.of(ability), policies, context ->
                assertThat(context.actionPolicy()).isEqualTo(expected));
    }
}
