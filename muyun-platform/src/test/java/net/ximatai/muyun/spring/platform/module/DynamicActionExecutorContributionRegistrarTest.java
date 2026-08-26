package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionContext;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionRequest;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutor;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutorDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutorRegistry;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicActionExecutorContributionRegistrarTest {
    @Test
    void shouldRejectContributionToStaticModule() {
        TestMemoryDao<PlatformModule> moduleDao = new TestMemoryDao<>();
        PlatformModuleService moduleService = new PlatformModuleService(moduleDao);
        PlatformModule module = new PlatformModule();
        module.setAlias("sales.contract");
        module.setApplicationAlias("sales");
        module.setTitle("合同");
        module.setModuleKind(ModuleKind.STATIC);
        moduleService.insert(module);
        PlatformModuleActionService actionService = new PlatformModuleActionService(new TestMemoryDao<>(), moduleService);
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBeansOfType(DynamicActionExecutor.class))
                .thenReturn(Map.of("testExecutor", new TestExecutor()));
        DynamicActionExecutorContributionRegistrar registrar = new DynamicActionExecutorContributionRegistrar(
                applicationContext, new ModuleActionContributionRegistrar(actionService), moduleService);

        assertThatThrownBy(registrar::run)
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("requires a dynamic module");
    }

    @Test
    void shouldAllowManualActionOnlyForDynamicModule() {
        TestMemoryDao<PlatformModule> moduleDao = new TestMemoryDao<>();
        PlatformModuleService moduleService = new PlatformModuleService(moduleDao);
        PlatformModule staticModule = module("sales.static", ModuleKind.STATIC);
        PlatformModule dynamicModule = module("sales.dynamic", ModuleKind.DYNAMIC);
        moduleService.insert(staticModule);
        moduleService.insert(dynamicModule);
        PlatformModuleActionService actionService = new PlatformModuleActionService(new TestMemoryDao<>(),
                moduleService, Optional.empty(), Optional.of(new DynamicActionExecutorRegistry(
                List.of(new ConfigurableExecutor()))));

        assertThatThrownBy(() -> actionService.insert(manualAction("sales.static")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("require a dynamic module");

        String actionId = actionService.insert(manualAction("sales.dynamic"));

        assertThat(actionService.select(actionId)).satisfies(action -> {
            assertThat(action.getModuleAlias()).isEqualTo("sales.dynamic");
            assertThat(action.getBindingType()).isEqualTo(ModuleActionBindingType.DYNAMIC_ACTION_EXECUTOR);
            assertThat(action.getBindingAlias()).isEqualTo("test.configurable");
        });
    }

    @Test
    void shouldDisableContributedActionsWhenExecutorIsNoLongerDeployed() {
        TestMemoryDao<PlatformModule> moduleDao = new TestMemoryDao<>();
        PlatformModuleService moduleService = new PlatformModuleService(moduleDao);
        PlatformModule module = new PlatformModule();
        module.setAlias("sales.contract");
        module.setApplicationAlias("sales");
        module.setTitle("合同");
        module.setModuleKind(ModuleKind.DYNAMIC);
        moduleService.insert(module);
        PlatformModuleActionService actionService = new PlatformModuleActionService(new TestMemoryDao<>(), moduleService);
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBeansOfType(DynamicActionExecutor.class))
                .thenReturn(Map.of("testExecutor", new TestExecutor()), Map.of());
        DynamicActionExecutorContributionRegistrar registrar = new DynamicActionExecutorContributionRegistrar(
                applicationContext, new ModuleActionContributionRegistrar(actionService), moduleService);

        registrar.run();
        assertThat(actionService.findByModuleAliasAndActionCode("sales.contract", "sync").getEnabled()).isTrue();

        registrar.run();

        assertThat(actionService.findByModuleAliasAndActionCode("sales.contract", "sync").getEnabled()).isFalse();
    }

    @PlatformDynamicActionContribution(moduleAlias = "sales.contract", actionCode = "sync", title = "同步")
    private static final class TestExecutor implements DynamicActionExecutor {
        @Override
        public String executorKey() {
            return "test.executor";
        }

        @Override
        public Object execute(DynamicActionExecutionContext context, DynamicActionExecutionRequest request) {
            return null;
        }
    }

    private PlatformModule module(String alias, ModuleKind kind) {
        PlatformModule module = new PlatformModule();
        module.setAlias(alias);
        module.setApplicationAlias("sales");
        module.setTitle(alias);
        module.setModuleKind(kind);
        return module;
    }

    private PlatformModuleAction manualAction(String moduleAlias) {
        PlatformModuleAction action = new PlatformModuleAction();
        action.setModuleAlias(moduleAlias);
        action.setActionCode("sync");
        action.setTitle("同步");
        action.setCategory(EntityActionCategory.CUSTOM);
        action.setActionLevel(EntityActionLevel.ANY);
        action.setExecutorType(EntityActionExecutorType.SERVICE);
        action.setExecutorKey("test.configurable");
        return action;
    }

    private static final class ConfigurableExecutor implements DynamicActionExecutor {
        @Override
        public String executorKey() {
            return "test.configurable";
        }

        @Override
        public DynamicActionExecutorDefinition definition() {
            return new DynamicActionExecutorDefinition(executorKey(), "可配置测试执行器", null,
                    List.of(EntityActionCategory.CUSTOM), List.of(EntityActionLevel.ANY), true);
        }

        @Override
        public Object execute(DynamicActionExecutionContext context, DynamicActionExecutionRequest request) {
            return null;
        }
    }
}
