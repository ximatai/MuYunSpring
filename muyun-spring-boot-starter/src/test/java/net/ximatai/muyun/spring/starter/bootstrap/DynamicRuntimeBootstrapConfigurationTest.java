package net.ximatai.muyun.spring.starter.bootstrap;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataOrchestrationService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.runtime.DynamicModuleRuntimeStartupActivationTask;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshService;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategoryService;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryItemService;
import net.ximatai.muyun.spring.platform.initialdata.InitialDataExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DynamicRuntimeBootstrapConfigurationTest {
    @Test
    void shouldActivateEachModuleOnceAndKeepStartingAfterInvalidMetadata() {
        var modules = mock(PlatformModuleService.class);
        var relations = mock(ModuleMetadataRelationService.class);
        var refresh = mock(PlatformDynamicRuntimeRefreshService.class);
        var orchestration = mock(ModuleMetadataOrchestrationService.class);
        var invalid = module("education.invalid");
        var ready = module("education.ready");
        when(modules.listVisibleModules()).thenReturn(List.of(invalid, ready));
        // Also provide the raw catalogue so a second restoration task cannot hide behind mocks.
        when(modules.list(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(invalid, ready));
        var main = new ModuleMetadataRelation();
        main.setModuleAlias(invalid.getAlias());
        when(relations.list(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(main));
        doThrow(new IllegalStateException("invalid persisted metadata"))
                .when(refresh).activateNow(invalid.getAlias());

        new ApplicationContextRunner()
                .withBean(MenuService.class, () -> mock(MenuService.class))
                .withBean(DictionaryCategoryService.class, () -> mock(DictionaryCategoryService.class))
                .withBean(DictionaryItemService.class, () -> mock(DictionaryItemService.class))
                .withBean(InitialDataExecutor.class, () -> mock(InitialDataExecutor.class))
                .withBean(PlatformModuleService.class, () -> modules)
                .withBean(PlatformModuleActionService.class, () -> mock(PlatformModuleActionService.class))
                .withBean(ModuleMetadataRelationService.class, () -> relations)
                .withBean(PlatformDynamicRuntimeRefreshService.class, () -> refresh)
                .withBean(ModuleMetadataOrchestrationService.class, () -> orchestration)
                .withUserConfiguration(MuYunSpringBootstrapConfiguration.class,
                        DynamicModuleRuntimeStartupActivationTask.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(PlatformBootstrapRunner.class).run(null);
                    verify(refresh).activateNow(invalid.getAlias());
                    verify(refresh).activateNow(ready.getAlias());
                    verifyNoMoreInteractions(refresh);
                });
    }

    private static PlatformModule module(String alias) {
        var module = new PlatformModule();
        module.setAlias(alias);
        module.setModuleKind(ModuleKind.DYNAMIC);
        module.setEnabled(true);
        return module;
    }
}
