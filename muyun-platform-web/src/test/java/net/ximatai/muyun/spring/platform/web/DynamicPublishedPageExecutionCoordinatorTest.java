package net.ximatai.muyun.spring.platform.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

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
