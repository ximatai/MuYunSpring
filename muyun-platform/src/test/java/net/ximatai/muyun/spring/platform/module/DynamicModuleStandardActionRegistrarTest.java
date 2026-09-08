package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicModuleStandardActionRegistrarTest {
    @Test
    void shouldKeepTenantOwnedModuleMutationsOutOfGlobalActionCatalogue() {
        java.util.concurrent.atomic.AtomicReference<DynamicModuleStandardActionRegistrar> listener =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.List<DynamicModuleChangedEvent> events = new java.util.ArrayList<>();
        PlatformModuleService modules = new PlatformModuleService(new TestMemoryDao<>(), event -> {
            if (event instanceof DynamicModuleChangedEvent changed) {
                events.add(changed);
                listener.get().reconcile(changed);
            }
        });
        PlatformModuleActionService actions = new PlatformModuleActionService(new TestMemoryDao<>(), modules);
        DynamicModuleStandardActionRegistrar registrar = new DynamicModuleStandardActionRegistrar(modules,
                new ModuleActionContributionRegistrar(actions));
        listener.set(registrar);
        PlatformModule module = new PlatformModule();
        module.setAlias("education.tenant_project");
        module.setApplicationAlias("education");
        module.setModuleKind(ModuleKind.DYNAMIC);
        module.setTitle("租户项目");
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            modules.insert(module);
            module.setTitle("租户项目更新");
            modules.update(module);
            assertThat(modules.select(module.getAlias()).getTitle()).isEqualTo("租户项目更新");
            assertThat(TenantContext.currentTenantId()).contains("tenant-a");
        }
        // Even direct restoration in system scope must not promote tenant definitions.
        registrar.register(module);
        assertThat(events).hasSize(2).allSatisfy(event -> assertThat(event.tenantId()).isEqualTo("tenant-a"));
        assertThat(actions.list(Criteria.of())).isEmpty();
    }

    @Test
    void shouldUseDefinitionOwnerRatherThanCallerTenantForGlobalModuleEvents() {
        java.util.concurrent.atomic.AtomicReference<DynamicModuleStandardActionRegistrar> listener =
                new java.util.concurrent.atomic.AtomicReference<>();
        PlatformModuleService modules = new PlatformModuleService(new TestMemoryDao<>(), event -> {
            if (event instanceof DynamicModuleChangedEvent changed) listener.get().reconcile(changed);
        });
        PlatformModuleActionService actions = new PlatformModuleActionService(new TestMemoryDao<>(), modules);
        listener.set(new DynamicModuleStandardActionRegistrar(modules, new ModuleActionContributionRegistrar(actions)));
        PlatformModule module = new PlatformModule();
        module.setAlias("education.global_project");
        module.setApplicationAlias("education");
        module.setModuleKind(ModuleKind.DYNAMIC);
        module.setTitle("共享项目");
        modules.insert(module);
        try (TenantContext.Scope ignored = TenantContext.use("tenant-request")) {
            modules.afterChanged(module);
            assertThat(TenantContext.currentTenantId()).contains("tenant-request");
        }
        assertThat(actions.list(Criteria.of())).hasSize(8);
    }

    @Test
    void shouldRegisterOnlyRuntimeSupportedStandardActionsForDynamicModule() {
        PlatformModuleService moduleService = new PlatformModuleService(new TestMemoryDao<>());
        PlatformModuleActionService actionService = new PlatformModuleActionService(new TestMemoryDao<>(), moduleService);
        DynamicModuleStandardActionRegistrar registrar = new DynamicModuleStandardActionRegistrar(moduleService,
                new ModuleActionContributionRegistrar(actionService));
        PlatformModule module = new PlatformModule();
        module.setAlias("education.project");
        module.setApplicationAlias("education");
        module.setTitle("项目任务");
        module.setModuleKind(ModuleKind.DYNAMIC);
        module.setMainCapabilityDeclarations(Set.of(
                EntityCapability.TREE.name(), EntityCapability.SORT.name(), EntityCapability.ENABLE.name()));
        moduleService.insert(module);

        registrar.register(module);

        assertThat(actionService.list(Criteria.of()).stream().map(PlatformModuleAction::getActionCode))
                .containsExactlyInAnyOrder("menu", "create", "view", "update", "delete", "batchDelete", "query", "reference",
                        "tree", "sort", "enable", "disable");
        assertThat(actionService.list(Criteria.of())).allSatisfy(action -> {
            assertThat(action.getSourceType()).isEqualTo(ModuleActionSourceType.DYNAMIC_MODULE);
            assertThat(action.getSourceId()).isEqualTo("education.project");
            assertThat(action.getSystemManaged()).isTrue();
        });
        assertThat(actionService.findByModuleAliasAndActionCode("education.project", "view").getDataAuth()).isFalse();
    }

    @Test
    void shouldKeepDataAuthorizationOutOfTheStandardActionCatalogue() {
        PlatformModuleService moduleService = new PlatformModuleService(new TestMemoryDao<>());
        PlatformModuleActionService actionService = new PlatformModuleActionService(new TestMemoryDao<>(), moduleService);
        DynamicModuleStandardActionRegistrar registrar = new DynamicModuleStandardActionRegistrar(moduleService,
                new ModuleActionContributionRegistrar(actionService));
        PlatformModule module = new PlatformModule();
        module.setAlias("education.scoped_project");
        module.setApplicationAlias("education");
        module.setTitle("范围项目");
        module.setModuleKind(ModuleKind.DYNAMIC);
        module.setMainCapabilityDeclarations(Set.of(EntityCapability.DATA_SCOPE.name()));
        moduleService.insert(module);

        registrar.register(module);

        assertThat(actionService.findByModuleAliasAndActionCode("education.scoped_project", "view").getDataAuth()).isFalse();
        assertThat(actionService.findByModuleAliasAndActionCode("education.scoped_project", "create").getDataAuth()).isFalse();
    }

    @Test
    void shouldDisableNoLongerSupportedCapabilityActionsDuringReconciliation() {
        PlatformModuleService moduleService = new PlatformModuleService(new TestMemoryDao<>());
        PlatformModuleActionService actionService = new PlatformModuleActionService(new TestMemoryDao<>(), moduleService);
        DynamicModuleStandardActionRegistrar registrar = new DynamicModuleStandardActionRegistrar(moduleService,
                new ModuleActionContributionRegistrar(actionService));
        PlatformModule module = new PlatformModule();
        module.setAlias("education.setting");
        module.setApplicationAlias("education");
        module.setTitle("设置");
        module.setModuleKind(ModuleKind.DYNAMIC);
        module.setMainCapabilityDeclarations(Set.of(EntityCapability.ENABLE.name()));
        moduleService.insert(module);
        registrar.register(module);

        module.setMainCapabilityDeclarations(Set.of());
        registrar.register(module);

        assertThat(actionService.findByModuleAliasAndActionCode("education.setting", "enable").getEnabled()).isFalse();
        assertThat(actionService.findByModuleAliasAndActionCode("education.setting", "disable").getEnabled()).isFalse();
        assertThat(actionService.findByModuleAliasAndActionCode("education.setting", "menu").getEnabled()).isTrue();
    }
}
