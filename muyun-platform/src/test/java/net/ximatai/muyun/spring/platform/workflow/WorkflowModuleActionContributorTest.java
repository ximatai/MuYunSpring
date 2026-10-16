package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.module.ModuleActionBindingType;
import net.ximatai.muyun.spring.platform.module.ModuleActionContribution;
import net.ximatai.muyun.spring.platform.module.ModuleActionContributionRegistrar;
import net.ximatai.muyun.spring.platform.module.ModuleActionSourceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkflowModuleActionContributorTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final net.ximatai.muyun.spring.platform.module.PlatformModuleService modules = mock(net.ximatai.muyun.spring.platform.module.PlatformModuleService.class);
    private final net.ximatai.muyun.spring.platform.module.PlatformModuleActionService actions = mock(net.ximatai.muyun.spring.platform.module.PlatformModuleActionService.class);
    private final ModuleActionContributionRegistrar registrar = mock(ModuleActionContributionRegistrar.class);
    private final WorkflowModuleActionContributor contributor = new WorkflowModuleActionContributor(registrar, modules, actions);

    @Test
    void shouldContributeActionForNonApprovalWorkflow() {
        WorkflowDefinition definition = definition(false);
        WorkflowVersion version = version();

        ModuleActionContribution contribution = contributor.contribution(definition, version);

        assertThat(contribution).isNotNull();
        assertThat(contribution.moduleAlias()).isEqualTo("sales.contract");
        assertThat(contribution.actionCode()).isEqualTo("syncWorkflow");
        assertThat(contribution.title()).isEqualTo("同步流程");
        assertThat(contribution.sourceType()).isEqualTo(ModuleActionSourceType.WORKFLOW_DEFINITION);
        assertThat(contribution.sourceId()).isEqualTo("def-1");
        assertThat(contribution.sourceVersionId()).isEqualTo("ver-1");
        assertThat(contribution.bindingType()).isEqualTo(ModuleActionBindingType.WORKFLOW_DEFINITION);
        assertThat(contribution.bindingId()).isEqualTo("def-1");
        assertThat(contribution.bindingAlias()).isEqualTo("sync");
    }

    @Test
    void shouldNotContributeActionForApprovalWorkflow() {
        assertThat(contributor.contribution(definition(true), version())).isNull();
    }

    @Test
    void shouldContributeRuntimeRecordActionsForApprovalWorkflow() {
        List<ModuleActionContribution> contributions = contributor.contributions(definition(true), version());

        assertThat(contributions).extracting(ModuleActionContribution::actionCode)
                .containsAll(WorkflowActionPolicyService.RUNTIME_RECORD_ACTION_CODES);
        assertThat(contributions)
                .filteredOn(contribution -> "approve".equals(contribution.actionCode()))
                .singleElement()
                .satisfies(contribution -> {
                    assertThat(contribution.moduleAlias()).isEqualTo("sales.contract");
                    assertThat(contribution.permissionActionCode()).isEqualTo("approve");
                    assertThat(contribution.actionLevel()).isEqualTo(net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel.RECORD);
                    assertThat(contribution.accessMode()).isEqualTo(net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode.AUTH_REQUIRED);
                    assertThat(contribution.actionAuth()).isTrue();
                    assertThat(contribution.dataAuth()).isTrue();
                    assertThat(contribution.sourceType()).isEqualTo(ModuleActionSourceType.WORKFLOW_RUNTIME);
                    assertThat(contribution.bindingType()).isNull();
                });
    }

    @Test
    void dynamicApprovalHasGrantableSubmissionWhileStaticEndpointKeepsItsOwnership() {
        var module = new net.ximatai.muyun.spring.platform.module.PlatformModule();
        module.setModuleKind(net.ximatai.muyun.spring.platform.module.ModuleKind.DYNAMIC);
        org.mockito.Mockito.when(modules.resolveVisibleModule("sales.contract")).thenReturn(module);
        assertThat(contributor.contributions(definition(true), version()))
                .filteredOn(action -> "submitApproval".equals(action.actionCode())).singleElement()
                .satisfies(action -> {
                    assertThat(action.permissionActionCode()).isEqualTo("submitApproval");
                    assertThat(action.sourceType()).isEqualTo(ModuleActionSourceType.WORKFLOW_RUNTIME);
                });
        module.setModuleKind(net.ximatai.muyun.spring.platform.module.ModuleKind.STATIC);
        assertThat(contributor.contributions(definition(true), version()))
                .extracting(ModuleActionContribution::actionCode).doesNotContain("submitApproval");
        module.setModuleKind(net.ximatai.muyun.spring.platform.module.ModuleKind.DYNAMIC);
        assertThat(contributor.contributions(definition(false), version()))
                .extracting(ModuleActionContribution::actionCode).doesNotContain("submitApproval");
    }

    @Test
    void publishingOtherWorkflowsPreservesTheModuleSubmissionDeclaration() {
        var modules = new net.ximatai.muyun.spring.platform.module.PlatformModuleService(
                new net.ximatai.muyun.spring.platform.support.TestMemoryDao<>(), event -> {});
        var module = new net.ximatai.muyun.spring.platform.module.PlatformModule();
        module.setId("sales.contract");
        module.setApplicationAlias("sales");
        module.setModuleKind(net.ximatai.muyun.spring.platform.module.ModuleKind.DYNAMIC);
        module.setTitle("合同");
        modules.insert(module);
        var actions = net.ximatai.muyun.spring.platform.support.ModuleActionTestServices.withDeclaredDataPolicy(
                new net.ximatai.muyun.spring.platform.support.TestMemoryDao<>(), modules);
        var contributor = new WorkflowModuleActionContributor(new ModuleActionContributionRegistrar(actions), modules, actions);
        contributor.registerPublishedWorkflowAction(definition(true), version());
        contributor.registerPublishedWorkflowAction(definition(false), version());
        var submission = actions.findByModuleAliasAndActionCode("sales.contract", "submitApproval");
        assertThat(submission.getEnabled()).isTrue();
        assertThat(submission.getSourceType()).isEqualTo(ModuleActionSourceType.WORKFLOW_RUNTIME);
        assertThat(actions.findByModuleAliasAndActionCode("sales.contract", "syncWorkflow").getEnabled()).isTrue();
    }

    @Test
    void shouldContributeRuntimeAndDefinitionActionForNonApprovalWorkflow() {
        List<ModuleActionContribution> contributions = contributor.contributions(definition(false), version());

        assertThat(contributions).extracting(ModuleActionContribution::actionCode)
                .containsAll(WorkflowActionPolicyService.RUNTIME_RECORD_ACTION_CODES)
                .contains("syncWorkflow");
    }

    @Test
    void shouldRejectNonApprovalWorkflowWithoutActionCode() {
        WorkflowDefinition definition = definition(false);
        definition.setActionCode(null);

        assertThatThrownBy(() -> contributor.contribution(definition, version()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("non-approval workflow actionCode must not be blank");
    }

    @Test
    void shouldLockTheSharedModuleDirectoryBeforeReconcilingPublishedActions() {
        var partitions = new java.util.ArrayList<String>();
        net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.configureMutationTransactionOperator(
                new net.ximatai.muyun.spring.ability.MutationTransactionOperator() {
                    @Override public <T> T execute(java.util.function.Supplier<T> work) { return work.get(); }
                    @Override public void lock(String scope, String key) { partitions.add(scope + ":" + key); }
                });
        org.mockito.Mockito.when(modules.resolveVisibleModule("sales.contract")).thenAnswer(invocation -> {
            assertThat(partitions).containsExactly("workflow.module.actions:sales.contract");
            return null;
        });

        contributor.registerPublishedWorkflowAction(definition(false), version());

        verify(registrar).registerAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void shouldRegisterPublishedWorkflowAction() {
        WorkflowDefinition definition = definition(false);
        WorkflowVersion version = version();

        contributor.registerPublishedWorkflowAction(definition, version);

        verify(registrar).registerAll(org.mockito.ArgumentMatchers.argThat(contributions ->
                contributions.stream().anyMatch(contribution ->
                        "syncWorkflow".equals(contribution.actionCode())
                                && "sync".equals(contribution.bindingAlias()))
                        && contributions.stream().anyMatch(contribution ->
                        "approve".equals(contribution.actionCode())
                                && contribution.dataAuth())));
    }

    private WorkflowDefinition definition(boolean approvalEnabled) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId("def-1");
        definition.setApplicationAlias("sales");
        definition.setModuleAlias("sales.contract");
        definition.setAlias("sync");
        definition.setTitle("同步流程");
        definition.setApprovalEnabled(approvalEnabled);
        definition.setActionCode("syncWorkflow");
        return definition;
    }

    private WorkflowVersion version() {
        WorkflowVersion version = new WorkflowVersion();
        version.setId("ver-1");
        version.setDefinitionId("def-1");
        version.setVersionNo(1);
        version.setPublishStatus(WorkflowPublishStatus.PUBLISHED);
        return version;
    }
}
