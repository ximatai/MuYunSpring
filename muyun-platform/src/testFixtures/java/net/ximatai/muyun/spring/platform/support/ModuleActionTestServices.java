package net.ximatai.muyun.spring.platform.support;

import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutorRegistry;
import net.ximatai.muyun.spring.platform.module.ModuleActionDataAuthResolver;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Catalogue fixtures declare permission facts explicitly; capability resolution has separate contract tests. */
public final class ModuleActionTestServices {
    private ModuleActionTestServices() { }

    public static PlatformModuleActionService withDeclaredDataPolicy(BaseDao<PlatformModuleAction, String> dao,
                                                                    PlatformModuleService modules) {
        return withDeclaredDataPolicy(dao, modules, Optional.empty(), Optional.empty());
    }

    public static PlatformModuleActionService withDeclaredDataPolicy(BaseDao<PlatformModuleAction, String> dao,
            PlatformModuleService modules, Optional<PlatformDynamicRuntimeRefreshCoordinator> refresh) {
        return withDeclaredDataPolicy(dao, modules, refresh, Optional.empty());
    }

    public static PlatformModuleActionService withDeclaredDataPolicy(BaseDao<PlatformModuleAction, String> dao,
            PlatformModuleService modules, Optional<PlatformDynamicRuntimeRefreshCoordinator> refresh,
            Optional<DynamicActionExecutorRegistry> executors) {
        var resolver = mock(ModuleActionDataAuthResolver.class);
        when(resolver.resolve(any())).thenAnswer(call -> call.getArgument(0, PlatformModuleAction.class).effectiveDataAuth());
        return new PlatformModuleActionService(dao, modules, refresh, executors,
                TestBeanProviders.of(ModuleActionDataAuthResolver.class, resolver));
    }
}
