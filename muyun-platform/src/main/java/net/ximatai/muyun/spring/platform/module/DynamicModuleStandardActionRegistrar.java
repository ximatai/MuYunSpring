package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.platform.metadata.*;
import net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
            PlatformAction.QUERY,
            // Field and child-reference resolution is a standard runtime transport. Its
            // source field declaration and target REFERENCE policy remain authoritative.
            PlatformAction.REFERENCE
    );

    private final PlatformModuleService moduleService;
    private final ModuleActionContributionRegistrar contributionRegistrar;
    private final ObjectProvider<ModuleMetadataRelationService> relations;
    private final ObjectProvider<MetadataService> metadata;
    private final ObjectProvider<MetadataFieldService> fields;
    private final TransactionTemplate transactions;

    public DynamicModuleStandardActionRegistrar(PlatformModuleService moduleService,
                                                ModuleActionContributionRegistrar contributionRegistrar) {
        this(moduleService, contributionRegistrar, null, null, null);
    }

    public DynamicModuleStandardActionRegistrar(PlatformModuleService moduleService,
                                                ModuleActionContributionRegistrar contributionRegistrar,
                                                ObjectProvider<ModuleMetadataRelationService> relations,
                                                ObjectProvider<MetadataService> metadata,
                                                ObjectProvider<MetadataFieldService> fields) {
        this(moduleService, contributionRegistrar, relations, metadata, fields, null);
    }

    @Autowired
    public DynamicModuleStandardActionRegistrar(PlatformModuleService moduleService,
                                                ModuleActionContributionRegistrar contributionRegistrar,
                                                ObjectProvider<ModuleMetadataRelationService> relations,
                                                ObjectProvider<MetadataService> metadata,
                                                ObjectProvider<MetadataFieldService> fields,
                                                PlatformTransactionManager transactionManager) {
        this.moduleService = moduleService;
        this.contributionRegistrar = contributionRegistrar;
        this.relations = relations;
        this.metadata = metadata;
        this.fields = fields;
        this.transactions = transactionManager == null ? null : new TransactionTemplate(transactionManager);
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
        if (event == null || !isGlobal(event.tenantId()) || event.moduleAlias() == null || event.moduleAlias().isBlank()) {
            return;
        }
        try (TenantContext.Scope ignored = TenantContext.system("reconcile dynamic module standard actions")) {
            register(moduleService.select(event.moduleAlias()));
        }
    }

    /** Metadata publication owns activation; synchronize authorization facts in the same transaction. */
    @EventListener
    public void reconcile(MetadataChangedEvent event) {
        if (event == null || !isGlobal(event.tenantId()) || relations == null) return;
        try (TenantContext.Scope ignored = TenantContext.system("reconcile metadata standard actions")) {
            Set<String> aliases = new java.util.LinkedHashSet<>();
            if (event.moduleAlias() != null) aliases.add(event.moduleAlias());
            if (event.metadataId() != null) {
                relations.getObject().list(Criteria.of().eq("metadataId", event.metadataId())
                        .eq("relationRole", RelationRole.MAIN).isNull("tenantId"), new PageRequest(0, Integer.MAX_VALUE))
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
        if (module == null || module.getModuleKind() != ModuleKind.DYNAMIC || module.getAlias() == null
                || !isGlobal(module.getTenantId())) {
            return;
        }
        try (TenantContext.Scope ignored = TenantContext.system("resolve dynamic standard actions")) {
            if (transactions == null) registerInTransaction(module, refreshRuntime);
            else transactions.executeWithoutResult(status -> registerInTransaction(module, refreshRuntime));
        }
    }

    private void registerInTransaction(PlatformModule module, boolean refreshRuntime) {
        CapabilitySnapshot snapshot = capabilities(module);
        Set<String> capabilities = snapshot.capabilities();
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
        boolean unchanged;
        try {
            PlatformModule current = moduleService.select(module.getAlias());
            unchanged = current != null && current.getModuleKind() == ModuleKind.DYNAMIC
                    && isGlobal(current.getTenantId()) && snapshot.equals(capabilities(current));
        } catch (PlatformException changedDefinition) {
            unchanged = false;
        }
        if (!unchanged) {
            throw new OptimisticLockException("Dynamic module capabilities changed during action reconciliation: "
                    + module.getAlias());
        }
    }

    private CapabilitySnapshot capabilities(PlatformModule module) {
        if (relations != null) {
            List<ModuleMetadataRelation> main = relations.getObject().list(Criteria.of()
                    .eq("moduleAlias", module.getAlias()).eq("relationRole", RelationRole.MAIN)
                    .isNull("tenantId"), new PageRequest(0, 1));
            if (!main.isEmpty()) {
                Metadata definition = metadata.getObject().select(main.getFirst().getMetadataId());
                if (definition == null || !isGlobal(definition.getTenantId())) throw new PlatformException("Module MAIN metadata is missing: " + module.getAlias());
                Set<String> capabilities = MetadataCapabilityCatalog.resolve(definition, RelationRole.MAIN,
                        definition.getCapabilityDeclarations() == null
                                ? fields.getObject().list(Criteria.of().eq("metadataId", definition.getId()).isNull("tenantId"),
                                        new PageRequest(0, Integer.MAX_VALUE)) : List.of())
                        .capabilities().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet());
                ModuleMetadataRelation relation = main.getFirst();
                return new CapabilitySnapshot(capabilities, relation.getId(), relation.getVersion(),
                        definition.getId(), definition.getVersion(), null);
            }
        }
        Set<String> intent = new java.util.LinkedHashSet<>(module.getMainCapabilityDeclarations() == null
                ? Set.of() : module.getMainCapabilityDeclarations());
        if (intent.contains(EntityCapability.TREE.name())) intent.add(EntityCapability.SORT.name());
        return new CapabilitySnapshot(Set.copyOf(intent), null, null, null, null, module.getVersion());
    }

    private record CapabilitySnapshot(Set<String> capabilities, String relationId, Integer relationVersion,
                                      String metadataId, Integer metadataVersion, Integer moduleVersion) { }

    // The action catalogue is global. Tenant-owned configuration must not become global grants.
    private static boolean isGlobal(String tenantId) {
        return tenantId == null || tenantId.isBlank();
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
