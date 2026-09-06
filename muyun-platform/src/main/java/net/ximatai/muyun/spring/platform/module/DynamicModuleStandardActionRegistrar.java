package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.metadata.*;
import net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Keeps platform-owned actions of a dynamic module as durable authorization facts.
 *
 * <p>Dynamic CRUD endpoints are supplied by the runtime, but their actions must still appear in
 * the module-action catalogue; otherwise a tenant administrator has nothing to grant.</p>
 */
@Service
public class DynamicModuleStandardActionRegistrar implements PlatformBootstrapTask {
    private static final Logger LOGGER = LoggerFactory.getLogger(DynamicModuleStandardActionRegistrar.class);
    private static final List<PlatformAction> BASE_ACTIONS = List.of(
            PlatformAction.MENU,
            PlatformAction.CREATE,
            PlatformAction.VIEW,
            PlatformAction.UPDATE,
            PlatformAction.DELETE,
            PlatformAction.BATCH_DELETE,
            PlatformAction.QUERY
    );

    private final PlatformModuleService moduleService;
    private final ModuleActionContributionRegistrar contributionRegistrar;
    private final ObjectProvider<ModuleMetadataRelationService> relations;
    private final ObjectProvider<MetadataService> metadata;
    private final ObjectProvider<MetadataFieldService> fields;

    public DynamicModuleStandardActionRegistrar(PlatformModuleService moduleService,
                                                ModuleActionContributionRegistrar contributionRegistrar) {
        this(moduleService, contributionRegistrar, null, null, null);
    }

    @Autowired
    public DynamicModuleStandardActionRegistrar(PlatformModuleService moduleService,
                                                ModuleActionContributionRegistrar contributionRegistrar,
                                                ObjectProvider<ModuleMetadataRelationService> relations,
                                                ObjectProvider<MetadataService> metadata,
                                                ObjectProvider<MetadataFieldService> fields) {
        this.moduleService = moduleService;
        this.contributionRegistrar = contributionRegistrar;
        this.relations = relations;
        this.metadata = metadata;
        this.fields = fields;
    }

    @Override
    public void run() {
        moduleService.listVisibleModules().stream()
                .filter(module -> module.getModuleKind() == ModuleKind.DYNAMIC)
                .forEach(module -> {
                    try {
                        register(module, false);
                    } catch (RuntimeException failure) {
                        LOGGER.warn("Skipped standard action restoration for dynamic module {}", module.getAlias(), failure);
                    }
                });
    }

    @Override
    public int order() {
        return 15;
    }

    /** Keeps the catalogue present from module creation onward, not only after a restart. */
    @EventListener
    public void reconcile(DynamicModuleChangedEvent event) {
        if (event == null || event.moduleAlias() == null || event.moduleAlias().isBlank()) {
            return;
        }
        try (TenantContext.Scope ignored = TenantContext.system("reconcile dynamic module standard actions")) {
            register(moduleService.select(event.moduleAlias()));
        }
    }

    /** Metadata publication owns activation; synchronize authorization facts in the same transaction. */
    @EventListener
    public void reconcile(MetadataChangedEvent event) {
        if (event == null || relations == null) return;
        try (TenantContext.Scope ignored = TenantContext.system("reconcile metadata standard actions")) {
            Set<String> aliases = new java.util.LinkedHashSet<>();
            if (event.moduleAlias() != null) aliases.add(event.moduleAlias());
            if (event.metadataId() != null) {
                relations.getObject().list(Criteria.of().eq("metadataId", event.metadataId())
                        .eq("relationRole", RelationRole.MAIN), new PageRequest(0, Integer.MAX_VALUE))
                        .forEach(relation -> aliases.add(relation.getModuleAlias()));
            }
            aliases.forEach(alias -> register(moduleService.select(alias), false));
        }
    }

    /** Reconciles one module whenever its business presentation or capability intent changes. */
    public void register(PlatformModule module) {
        register(module, true);
    }

    private void register(PlatformModule module, boolean refreshRuntime) {
        if (module == null || module.getModuleKind() != ModuleKind.DYNAMIC || module.getAlias() == null) {
            return;
        }
        try (TenantContext.Scope ignored = TenantContext.system("resolve dynamic standard actions")) {
            Set<String> capabilities = capabilities(module);
            List<PlatformAction> actions = new ArrayList<>(BASE_ACTIONS);
            if (capabilities.contains(EntityCapability.TREE.name())) actions.add(PlatformAction.TREE);
            if (capabilities.contains(EntityCapability.SORT.name())) actions.add(PlatformAction.SORT);
            if (capabilities.contains(EntityCapability.ENABLE.name())) {
                actions.add(PlatformAction.ENABLE);
                actions.add(PlatformAction.DISABLE);
            }
            List<ModuleActionContribution> contributions = actions.stream()
                    .map(action -> contribution(module, action))
                    .toList();
            if (refreshRuntime) contributionRegistrar.registerAll(contributions);
            else contributionRegistrar.registerAllWithoutRuntimeRefresh(contributions);
        }
    }

    private Set<String> capabilities(PlatformModule module) {
        if (relations != null) {
            List<ModuleMetadataRelation> main = relations.getObject().list(Criteria.of()
                    .eq("moduleAlias", module.getAlias()).eq("relationRole", RelationRole.MAIN), new PageRequest(0, 1));
            if (!main.isEmpty()) {
                Metadata definition = metadata.getObject().select(main.getFirst().getMetadataId());
                if (definition == null) throw new PlatformException("Module MAIN metadata is missing: " + module.getAlias());
                return MetadataCapabilityCatalog.resolve(definition, RelationRole.MAIN,
                        definition.getCapabilityDeclarations() == null
                                ? fields.getObject().list(Criteria.of().eq("metadataId", definition.getId()),
                                        new PageRequest(0, Integer.MAX_VALUE)) : List.of())
                        .capabilities().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet());
            }
        }
        Set<String> intent = new java.util.LinkedHashSet<>(module.getMainCapabilityDeclarations() == null
                ? Set.of() : module.getMainCapabilityDeclarations());
        if (intent.contains(EntityCapability.TREE.name())) intent.add(EntityCapability.SORT.name());
        return intent;
    }

    private static ModuleActionContribution contribution(PlatformModule module, PlatformAction action) {
        return new ModuleActionContribution(
                module.getAlias(), null, action.code(), action.permissionActionCode(), action.title(),
                null, null, null, action.actionAuth(), false, action.defaultGrantPolicy(),
                null, null, null, null,
                ModuleActionSourceType.DYNAMIC_MODULE, module.getAlias(), null,
                null, null, null, true
        );
    }
}
