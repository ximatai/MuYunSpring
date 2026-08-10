package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.ability.PlatformManagedMutationContext;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.application.StaticApplicationDefinitionCatalog;
import net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class StaticModuleDefinitionRegistrar implements PlatformBootstrapTask {
    private final PlatformModuleService moduleService;
    private final PlatformModuleActionService actionService;
    private final StaticModuleRegistrationSource definitionSource;
    private final boolean disablesStaleSystemManagedModules;
    private final StaticApplicationDefinitionCatalog applicationCatalog;

    public StaticModuleDefinitionRegistrar(PlatformModuleService moduleService,
                                           PlatformModuleActionService actionService,
                                           List<? extends StaticModuleRegistration> definitions) {
        this(moduleService, actionService, definitions, List.of());
    }

    public StaticModuleDefinitionRegistrar(PlatformModuleService moduleService,
                                           PlatformModuleActionService actionService,
                                           List<? extends StaticModuleRegistration> definitions,
                                           List<? extends StaticModuleRegistrationSource> sources) {
        this(moduleService, actionService,
                new FixedStaticModuleRegistrationSource(definitions, sources),
                sources != null && !sources.isEmpty());
    }

    public StaticModuleDefinitionRegistrar(PlatformModuleService moduleService,
                                           PlatformModuleActionService actionService,
                                           StaticModuleRegistrationSource definitionSource,
                                           boolean disablesStaleSystemManagedModules) {
        this(moduleService, actionService, definitionSource, disablesStaleSystemManagedModules, null);
    }

    public StaticModuleDefinitionRegistrar(PlatformModuleService moduleService,
                                           PlatformModuleActionService actionService,
                                           StaticModuleRegistrationSource definitionSource,
                                           boolean disablesStaleSystemManagedModules,
                                           StaticApplicationDefinitionCatalog applicationCatalog) {
        this.moduleService = moduleService;
        this.actionService = actionService;
        this.definitionSource = definitionSource == null
                ? StaticModuleRegistrationSource.empty()
                : definitionSource;
        this.disablesStaleSystemManagedModules = disablesStaleSystemManagedModules;
        this.applicationCatalog = applicationCatalog;
    }

    @Override
    public void run() {
        registerAll();
    }

    @Override
    public int order() {
        return 0;
    }

    public void registerAll() {
        try (TenantContext.Scope ignored = TenantContext.system("register static modules")) {
            List<? extends StaticModuleRegistration> allDefinitions = definitionSource.definitions();
            StaticModuleRegistrationValidator.validate(allDefinitions);
            StaticDeclarationPreflightTask.validateApplicationOwnership(allDefinitions, applicationCatalog);
            PlatformManagedMutationContext.runAsPlatformManaged(() -> {
                for (StaticModuleRegistration definition : allDefinitions) {
                    registerModule(definition);
                    registerActions(definition);
                }
                disableStaleSystemManagedModules(allDefinitions);
            });
        }
    }

    private void registerModule(StaticModuleRegistration definition) {
        PlatformModule module = moduleService.select(definition.moduleAlias());
        if (module == null) {
            module = new PlatformModule();
            module.setAlias(definition.moduleAlias());
            module.setApplicationAlias(definition.applicationAlias());
            module.setParentId(definition.parentModuleAlias() == null
                    ? TreeAbility.ROOT_ID
                    : definition.parentModuleAlias());
            module.setTitle(definition.title());
            module.setModuleKind(ModuleKind.STATIC);
            module.setEntryType(definition.entryType());
            module.setEntryRoute(definition.entryRoute());
            module.setEntryExternalUrl(definition.entryExternalUrl());
            module.setSystemManaged(Boolean.TRUE);
            moduleService.insert(module);
            return;
        }
        module.setApplicationAlias(definition.applicationAlias());
        module.setParentId(definition.parentModuleAlias() == null
                ? TreeAbility.ROOT_ID
                : definition.parentModuleAlias());
        module.setTitle(definition.title());
        module.setModuleKind(ModuleKind.STATIC);
        module.setEntryType(definition.entryType());
        module.setEntryRoute(definition.entryRoute());
        module.setEntryExternalUrl(definition.entryExternalUrl());
        module.setSystemManaged(Boolean.TRUE);
        moduleService.update(module);
    }

    private void registerActions(StaticModuleRegistration definition) {
        Set<String> declaredActionCodes = definition.actions().stream()
                .map(StaticModuleActionDefinition::actionCode)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        int order = 1;
        for (StaticModuleActionDefinition actionDefinition : definition.actions()) {
            PlatformModuleAction action = actionService.findByModuleAliasAndActionCode(
                    definition.moduleAlias(), actionDefinition.actionCode());
            if (action == null) {
                action = new PlatformModuleAction();
                action.setModuleAlias(definition.moduleAlias());
                action.setActionCode(actionDefinition.actionCode());
            }
            action.setPermissionActionCode(actionDefinition.permissionActionCode());
            action.setTitle(actionDefinition.title());
            action.setCategory(actionDefinition.category());
            action.setActionLevel(actionDefinition.actionLevel());
            action.setAccessMode(actionDefinition.accessMode());
            action.setActionAuth(actionDefinition.actionAuth());
            action.setDataAuth(actionDefinition.dataAuth());
            action.setDefaultGrantPolicy(actionDefinition.defaultGrantPolicy());
            action.setExecutorType(actionDefinition.executorType());
            action.setExecutorKey(actionDefinition.executorKey());
            action.setSourceType(ModuleActionSourceType.STATIC_MODULE);
            action.setSourceId(definition.moduleAlias());
            action.setSystemManaged(Boolean.TRUE);
            action.setEnabled(Boolean.TRUE);
            action.setSortOrder(order++);
            if (action.getId() == null || action.getId().isBlank()) {
                actionService.insert(action);
            } else {
                actionService.update(action);
            }
        }
        disableStaleSystemManagedActions(definition.moduleAlias(), declaredActionCodes);
    }

    /**
     * Static declarations are authoritative for actions contributed by the module itself.
     * Keep stale rows for governance history, but do not leave removed operations available
     * through the runtime action catalog after an application upgrade.
     */
    private void disableStaleSystemManagedActions(String moduleAlias, Set<String> declaredActionCodes) {
        for (PlatformModuleAction action : actionService.listSystemManagedActions(moduleAlias)) {
            if (action.getSourceType() != ModuleActionSourceType.STATIC_MODULE
                    || !moduleAlias.equals(action.getSourceId())
                    || declaredActionCodes.contains(action.getActionCode())
                    || !Boolean.TRUE.equals(action.getEnabled())) {
                continue;
            }
            actionService.disable(action.getId());
        }
    }

    private void disableStaleSystemManagedModules(List<? extends StaticModuleRegistration> definitions) {
        if (!disablesStaleSystemManagedModules) {
            return;
        }
        Set<String> currentModuleAliases = new HashSet<>();
        for (StaticModuleRegistration definition : definitions) {
            currentModuleAliases.add(definition.moduleAlias());
        }
        for (PlatformModule module : moduleService.listSystemManagedStaticModules()) {
            if (currentModuleAliases.contains(module.getAlias())) {
                continue;
            }
            for (PlatformModuleAction action : actionService.listSystemManagedActions(module.getAlias())) {
                actionService.disable(action.getId());
            }
            moduleService.disable(module.getAlias());
        }
    }

    private static final class FixedStaticModuleRegistrationSource implements StaticModuleRegistrationSource {
        private final List<? extends StaticModuleRegistration> definitions;
        private final List<? extends StaticModuleRegistrationSource> sources;

        private FixedStaticModuleRegistrationSource(List<? extends StaticModuleRegistration> definitions,
                                                     List<? extends StaticModuleRegistrationSource> sources) {
            this.definitions = definitions == null ? List.of() : List.copyOf(definitions);
            this.sources = sources == null ? List.of() : List.copyOf(sources);
        }

        @Override
        public List<? extends StaticModuleRegistration> definitions() {
            if (sources.isEmpty()) {
                return definitions;
            }
            java.util.ArrayList<StaticModuleRegistration> all = new java.util.ArrayList<>(definitions);
            for (StaticModuleRegistrationSource source : sources) {
                all.addAll(source.definitions());
            }
            return List.copyOf(all);
        }
    }
}
