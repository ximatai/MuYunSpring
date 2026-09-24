package net.ximatai.muyun.spring.platform.runtime;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.platform.initialdata.InitialDataBootstrapTask;
import net.ximatai.muyun.spring.platform.initialdata.InitialDataExecutor;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataOrchestrationService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DynamicModuleRuntimeStartupActivationTaskTest {
    @Test
    void shouldActivateOnlyDynamicModulesWhoseMainMetadataHasBeenPublished() {
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        ModuleMetadataRelationService relationService = mock(ModuleMetadataRelationService.class);
        DynamicRuntimeActivationService refreshService = mock(DynamicRuntimeActivationService.class);
        PlatformModule publishedModule = module("education.exam", ModuleKind.DYNAMIC);
        when(moduleService.listVisibleModules()).thenReturn(List.of(
                publishedModule,
                module("education.draft", ModuleKind.DYNAMIC),
                module("iam.user", ModuleKind.STATIC)));
        when(relationService.list(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(new ModuleMetadataRelation()), List.of());

        DynamicModuleRuntimeStartupActivationTask task = new DynamicModuleRuntimeStartupActivationTask(
                moduleService, relationService, refreshService);

        task.run();

        verify(refreshService).restoreAtStartup("education.exam");
        verify(refreshService).trackedModuleAliases();
        verifyNoMoreInteractions(refreshService);
    }

    @Test
    void shouldRunAfterInitialDataToRecoverMetadataPublishedDuringStartup() {
        DynamicModuleRuntimeStartupActivationTask task = new DynamicModuleRuntimeStartupActivationTask(
                mock(PlatformModuleService.class), mock(ModuleMetadataRelationService.class),
                mock(DynamicRuntimeActivationService.class));

        assertThat(task).isInstanceOf(PlatformBootstrapTask.class);
        assertThat(task.order()).isGreaterThan(new InitialDataBootstrapTask(mock(InitialDataExecutor.class)).order());
    }

    @Test
    void shouldKeepStartingWhenOnePublishedModuleCannotBeActivated() {
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        ModuleMetadataRelationService relationService = mock(ModuleMetadataRelationService.class);
        DynamicRuntimeActivationService refreshService = mock(DynamicRuntimeActivationService.class);
        when(moduleService.listVisibleModules()).thenReturn(List.of(
                module("education.invalid", ModuleKind.DYNAMIC), module("education.ready", ModuleKind.DYNAMIC)));
        when(relationService.list(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(new ModuleMetadataRelation()));
        org.mockito.Mockito.doThrow(new IllegalStateException("broken metadata"))
                .when(refreshService).restoreAtStartup("education.invalid");

        new DynamicModuleRuntimeStartupActivationTask(moduleService, relationService, refreshService).run();

        verify(refreshService).restoreAtStartup("education.invalid");
        verify(refreshService).restoreAtStartup("education.ready");
    }

    private static PlatformModule module(String alias, ModuleKind kind) {
        PlatformModule module = new PlatformModule();
        module.setAlias(alias);
        module.setModuleKind(kind);
        return module;
    }
}
