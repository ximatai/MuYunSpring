package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DynamicModuleRuntimeStartupActivatorTest {
    @Test
    void shouldActivateOnlyDynamicModulesWhoseMainMetadataHasBeenPublished() throws Exception {
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        ModuleMetadataRelationService relationService = mock(ModuleMetadataRelationService.class);
        PlatformDynamicRuntimeRefreshService refreshService = mock(PlatformDynamicRuntimeRefreshService.class);
        PlatformModule releasedModule = module("education.exam", ModuleKind.DYNAMIC);
        assertThat(releasedModule.getModuleKind()).isEqualTo(ModuleKind.DYNAMIC);
        when(moduleService.listVisibleModules()).thenReturn(List.of(
                releasedModule,
                module("education.draft", ModuleKind.DYNAMIC),
                module("iam.user", ModuleKind.STATIC)));
        when(relationService.list(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(new ModuleMetadataRelation()), List.of());

        new DynamicModuleRuntimeStartupActivator(moduleService, relationService, refreshService)
                .run(new DefaultApplicationArguments());

        verify(refreshService).activateNow("education.exam");
        verifyNoMoreInteractions(refreshService);
    }

    private static PlatformModule module(String alias, ModuleKind kind) {
        PlatformModule module = new PlatformModule();
        module.setAlias(alias);
        module.setModuleKind(kind);
        return module;
    }
}
