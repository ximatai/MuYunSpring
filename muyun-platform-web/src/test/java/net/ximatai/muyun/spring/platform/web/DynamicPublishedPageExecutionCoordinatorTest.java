package net.ximatai.muyun.spring.platform.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DynamicPublishedPageExecutionCoordinatorTest {
    @Test
    void shouldDeferRuntimeContextResolutionUntilPublicationChanges() {
        @SuppressWarnings("unchecked")
        ObjectProvider<PlatformModuleRuntimeContextService> runtimeContexts = mock(ObjectProvider.class);
        ModuleExecutionPlanCatalog planCatalog = new ModuleExecutionPlanCatalog(
                new StaticModuleDefinitionCatalog(List.of()), new ListQuerySummaryContributorCatalog(List.of()));

        new DynamicPublishedPageExecutionCoordinator(runtimeContexts, planCatalog);

        verifyNoInteractions(runtimeContexts);
    }

    @Test
    void shouldRemoveInstalledPlanWhenTheEffectivePublishedPageNoLongerResolves() {
        String moduleAlias = "iam.user";
        ModuleExecutionPlanCatalog planCatalog = new ModuleExecutionPlanCatalog(
                new StaticModuleDefinitionCatalog(List.of()), new ListQuerySummaryContributorCatalog(List.of()));
        planCatalog.replaceDynamicPlan(moduleAlias, Optional.of(plan(moduleAlias, "dynamic-runtime-1-page-r1", false)));
        PlatformModuleRuntimeContextService runtimeContextService = mock(PlatformModuleRuntimeContextService.class);
        when(runtimeContextService.dynamicExecutionPlan(moduleAlias)).thenReturn(Optional.empty());

        new DynamicPublishedPageExecutionCoordinator(runtimeContextService, planCatalog)
                .prepareAfterPublishedConfigurationChange(moduleAlias);

        assertThat(planCatalog.find(moduleAlias)).isEmpty();
    }

    @Test
    void shouldKeepInstalledPlanWhenPublicationCandidateReferencesMissingSummaryContributor() {
        String moduleAlias = "iam.user";
        ModuleExecutionPlanCatalog planCatalog = new ModuleExecutionPlanCatalog(
                new StaticModuleDefinitionCatalog(List.of()), new ListQuerySummaryContributorCatalog(List.of()));
        ModuleExecutionPlan installed = plan(moduleAlias, "dynamic-runtime-1-ui-1", false);
        planCatalog.replaceDynamicPlan(moduleAlias, Optional.of(installed));

        PlatformModuleRuntimeContextService runtimeContextService = mock(PlatformModuleRuntimeContextService.class);
        when(runtimeContextService.dynamicExecutionPlan(moduleAlias)).thenReturn(Optional.of(
                plan(moduleAlias, "dynamic-runtime-1-ui-2", true)));
        DynamicPublishedPageExecutionCoordinator coordinator = new DynamicPublishedPageExecutionCoordinator(
                runtimeContextService, planCatalog);

        assertThatThrownBy(() -> coordinator.prepareAfterPublishedConfigurationChange(moduleAlias))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no list query summary contributor: iam.user.iam.active-user-count");
        assertThat(planCatalog.find(moduleAlias)).containsSame(installed);
    }

    @Test
    void shouldInstallCompiledGroupedCandidateOnlyAfterCommitAndRetainOldPlanWhenCompilationFails() {
        String moduleAlias = "sales.contract";
        ModuleExecutionPlanCatalog catalog = new ModuleExecutionPlanCatalog(
                new StaticModuleDefinitionCatalog(List.of()), new ListQuerySummaryContributorCatalog(List.of()));
        ModuleExecutionPlan installed = plan(moduleAlias, "published-r1", false);
        ModuleExecutionPlan candidate = groupedPlanFromManagementRoot(moduleAlias, "published-r2");
        assertThat(candidate.uiDescriptor().page().list().querySummaries()).singleElement().satisfies(summary -> {
            assertThat(summary.source()).isEqualTo(PageListQuerySummaryDefinition.Source.GROUPED);
            assertThat(summary.groupByField()).isEqualTo("status");
            assertThat(summary.fieldName()).isEqualTo("amount");
        });
        catalog.replaceDynamicPlan(moduleAlias, Optional.of(installed));
        PlatformModuleRuntimeContextService context = mock(PlatformModuleRuntimeContextService.class);
        when(context.dynamicExecutionPlan(moduleAlias)).thenReturn(Optional.of(candidate));
        DynamicPublishedPageExecutionCoordinator coordinator = new DynamicPublishedPageExecutionCoordinator(context, catalog);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            coordinator.prepareAfterPublishedConfigurationChange(moduleAlias);
            assertThat(catalog.find(moduleAlias)).containsSame(installed);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertThat(catalog.find(moduleAlias)).containsSame(candidate);

            when(context.dynamicExecutionPlan(moduleAlias)).thenThrow(
                    new IllegalArgumentException("grouped list query summary field is not eligible: sales.contract.title"));
            assertThatThrownBy(() -> coordinator.prepareAfterPublishedConfigurationChange(moduleAlias))
                    .hasMessageContaining("not eligible");
            assertThat(catalog.find(moduleAlias)).containsSame(candidate);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private static ModuleExecutionPlan groupedPlanFromManagementRoot(String moduleAlias, String versionKey) {
        var page = new net.ximatai.muyun.spring.platform.ui.PlatformPageDefinition();
        page.setId("page-contract"); page.setModuleAlias(moduleAlias);
        page.setContractType(net.ximatai.muyun.spring.platform.ui.PlatformPageContractType.MANAGEMENT);
        var revision = new net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevision();
        revision.setId(versionKey); revision.setTemplateAlias("management"); revision.setTemplateVersion(4);
        revision.setStatus(net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionStatus.PUBLISHED);
        revision.setUiTreeJson("""
                {"template":"management","templateVersion":4,"mode":"LIST_CARD","quickSearchFields":[],"actions":[],
                 "querySummaries":[{"key":"statusBreakdown","label":"状态汇总","source":"GROUPED","groupByField":"status","fieldName":"amount"}],
                 "nodes":[{"slot":"list","title":"列表","fields":["title"]},{"slot":"form","title":"详情","fields":["title"]}]}
                """);
        ModuleUiDefinition definition = PageRevisionModuleUiDefinitionAdapter.fromPublishedRevision(page, revision,
                new DynamicPageCompilationContext(net.ximatai.muyun.spring.platform.module.DynamicModuleOverviewMode.LIST_CARD,
                        java.util.Map.of("title", "合同名称", "amount", "金额"), java.util.Set.of(), java.util.Map.of()));
        return new ModuleExecutionPlan(moduleAlias, versionKey, ModuleUiDescriptorCompiler.compile(definition),
                new ResolvedModuleReadModel(moduleAlias, "contract", List.of()), List.of());
    }

    private static ModuleExecutionPlan plan(String moduleAlias, String versionKey, boolean withContributorSummary) {
        ModuleUiDefinition definition = ModuleUiDefinition.builder(moduleAlias)
                .page(PageTemplates.listDetailCard(page -> page
                        .list(list -> list.fields(fields -> fields.field("username", field -> { }))
                                .querySummaries(summaries -> {
                                    if (withContributorSummary) {
                                        summaries.item("onlineUsers", summary -> summary
                                                .label("在线").contributor("iam.active-user-count"));
                                    }
                                }))
                        .detail(detail -> detail.editor(editor -> editor.field("username", field -> { })))))
                .build();
        return new ModuleExecutionPlan(moduleAlias, versionKey, ModuleUiDescriptorCompiler.compile(definition),
                new ResolvedModuleReadModel(moduleAlias, "user", List.of()), List.of());
    }
}
