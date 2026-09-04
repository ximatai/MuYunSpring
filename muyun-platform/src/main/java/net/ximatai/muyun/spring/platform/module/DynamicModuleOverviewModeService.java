package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.metadata.Metadata;
import net.ximatai.muyun.spring.platform.metadata.MetadataCapabilityCatalog;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetApplyCommand;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetApplyService;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPreview;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPreviewCommand;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPreviewService;
import net.ximatai.muyun.spring.platform.metadata.MetadataService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owns the overview selection; capability facts are derived from MAIN metadata. */
@Service
public class DynamicModuleOverviewModeService {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);
    private final PlatformModuleService moduleService;
    private final ModuleMetadataRelationService relationService;
    private final MetadataService metadataService;
    private final MetadataFieldService fieldService;
    private final MetadataRelationChangeSetPreviewService changeSetPreviewService;
    private final MetadataRelationChangeSetApplyService changeSetApplyService;

    public DynamicModuleOverviewModeService(PlatformModuleService moduleService,
                                            ModuleMetadataRelationService relationService,
                                            MetadataService metadataService,
                                            MetadataFieldService fieldService,
                                            MetadataRelationChangeSetPreviewService changeSetPreviewService,
                                            MetadataRelationChangeSetApplyService changeSetApplyService) {
        this.moduleService = moduleService;
        this.relationService = relationService;
        this.metadataService = metadataService;
        this.fieldService = fieldService;
        this.changeSetPreviewService = changeSetPreviewService;
        this.changeSetApplyService = changeSetApplyService;
    }

    public DynamicModuleOverviewModeSnapshot get(String moduleAlias) { return snapshot(requireDynamicModule(moduleAlias)); }

    @Transactional
    public DynamicModuleOverviewModeSnapshot save(String moduleAlias, DynamicModuleOverviewModeSaveCommand command) {
        if (command == null || command.overviewMode() == null) throw new IllegalArgumentException("dynamic module overview mode is required");
        PlatformModule module = requireDynamicModule(moduleAlias);
        MainMetadata main = mainMetadata(module.getAlias(), module);
        // Before a MAIN model exists this write only changes durable module intent. Runtime/schema
        // refreshes can legitimately advance the module version in the meantime, so they must not
        // make the configuration screen impossible to save.
        Set<EntityCapability> effectiveCapabilities = main.relationId() == null
                ? applyModuleCapabilitySelections(configuredCapabilities(module), command)
                : applyCapabilitySelections(module.getAlias(), main, command);
        if (main.relationId() != null) effectiveCapabilities = applyCapabilityRemovals(main, command, effectiveCapabilities);
        if (main.relationId() != null) applyDataScopeSelection(main, command.dataScopeEnabled());
        if ((!Boolean.FALSE.equals(command.dataScopeEnabled()) && main.capabilities().contains(EntityCapability.DATA_SCOPE))
                || Boolean.TRUE.equals(command.dataScopeEnabled())) {
            effectiveCapabilities = new java.util.LinkedHashSet<>(effectiveCapabilities);
            effectiveCapabilities.add(EntityCapability.DATA_SCOPE);
        }
        persistCapabilityIntent(module, effectiveCapabilities);
        effectiveCapabilities = requiredBy(command.overviewMode(), effectiveCapabilities);
        validate(command.overviewMode(), effectiveCapabilities);
        module.setOverviewMode(command.overviewMode());
        moduleService.update(module);
        return snapshot(moduleService.select(module.getAlias()));
    }

    private DynamicModuleOverviewModeSnapshot snapshot(PlatformModule module) {
        MainMetadata main = mainMetadata(module.getAlias(), module);
        return new DynamicModuleOverviewModeSnapshot(module.getAlias(), module.getVersion(), effectiveMode(module),
                main.metadataId(), main.metadataVersion(), main.capabilities().stream().map(Enum::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    private PlatformModule requireDynamicModule(String moduleAlias) {
        String alias = PlatformNameRules.requireModuleAlias(moduleAlias);
        PlatformModule module = moduleService.select(alias);
        if (module == null || module.getModuleKind() != ModuleKind.DYNAMIC) {
            throw new PlatformException("Dynamic module overview mode requires dynamic module: " + alias);
        }
        return module;
    }

    private MainMetadata mainMetadata(String moduleAlias, PlatformModule module) {
        List<ModuleMetadataRelation> relations = relationService.list(Criteria.of().eq("moduleAlias", moduleAlias)
                .eq("relationRole", RelationRole.MAIN), ALL);
        if (relations.isEmpty()) return new MainMetadata(moduleAlias, null, null, null, configuredCapabilities(module));
        ModuleMetadataRelation relation = relations.getFirst();
        Metadata metadata = metadataService.select(relation.getMetadataId());
        if (metadata == null) throw new PlatformException("Module MAIN metadata is missing: " + relations.getFirst().getMetadataId());
        Set<EntityCapability> capabilities = new java.util.LinkedHashSet<>(MetadataCapabilityCatalog.resolve(metadata,
                RelationRole.MAIN, fieldService.list(Criteria.of().eq("metadataId", metadata.getId()), ALL)).capabilities());
        if (Boolean.TRUE.equals(metadata.getDataScopeEnabled())) capabilities.add(EntityCapability.DATA_SCOPE);
        return new MainMetadata(moduleAlias, relation.getId(), metadata.getId(), metadata.getVersion(), capabilities);
    }

    private Set<EntityCapability> applyCapabilitySelections(String moduleAlias, MainMetadata main,
                                                            DynamicModuleOverviewModeSaveCommand command) {
        Map<EntityCapability, Boolean> selections = command.capabilitySelections() == null ? Map.of()
                : Map.copyOf(command.capabilitySelections());
        Map<EntityCapability, Boolean> additions = selections.entrySet().stream()
                .filter(entry -> Boolean.TRUE.equals(entry.getValue()))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if (main.relationId() == null) {
            if (command.expectedMainMetadataVersion() != null || !additions.isEmpty()) {
                throw new PlatformException("Dynamic module has no MAIN metadata to configure capabilities");
            }
            return Set.of();
        }
        MetadataRelationChangeSetPreviewCommand proposal = new MetadataRelationChangeSetPreviewCommand(
                command.expectedMainMetadataVersion(), additions, List.of());
        MetadataRelationChangeSetPreview preview = changeSetPreviewService.preview(moduleAlias, main.relationId(), proposal);
        if (!preview.valid()) {
            throw new PlatformException("Metadata capability selection is invalid: " + preview.errors());
        }
        if (!additions.isEmpty()) {
            changeSetApplyService.apply(moduleAlias, main.relationId(),
                    new MetadataRelationChangeSetApplyCommand(proposal, preview.proposalFingerprint()));
        }
        return preview.effectiveCapabilities();
    }

    /**
     * Module configuration is the durable source of intent.  A metadata model is merely its
     * current projection target, so the governance page stays usable while no MAIN model exists.
     */
    private Set<EntityCapability> applyModuleCapabilitySelections(Set<EntityCapability> current,
                                                                   DynamicModuleOverviewModeSaveCommand command) {
        Set<EntityCapability> selected = new java.util.LinkedHashSet<>(current);
        if (command.capabilitySelections() != null) {
            command.capabilitySelections().forEach((capability, enabled) -> {
                if (capability == EntityCapability.DATA_SCOPE) return;
                if (Boolean.TRUE.equals(enabled)) selected.add(capability);
                else selected.remove(capability);
            });
        }
        if (command.dataScopeEnabled() != null) {
            if (command.dataScopeEnabled()) selected.add(EntityCapability.DATA_SCOPE);
            else selected.remove(EntityCapability.DATA_SCOPE);
        }
        return selected;
    }

    private Set<EntityCapability> applyCapabilityRemovals(MainMetadata main,
                                                           DynamicModuleOverviewModeSaveCommand command,
                                                           Set<EntityCapability> effectiveCapabilities) {
        if (command.capabilitySelections() == null) return effectiveCapabilities;
        Set<EntityCapability> result = new java.util.LinkedHashSet<>(effectiveCapabilities);
        // JSON object member order is not a dependency order.  Remove dependent contracts first.
        for (EntityCapability capability : List.of(EntityCapability.TREE, EntityCapability.SORT, EntityCapability.ENABLE)) {
            if (!Boolean.FALSE.equals(command.capabilitySelections().get(capability))
                    || !main.capabilities().contains(capability)) continue;
            if (capability == EntityCapability.ENABLE) {
                changeSetApplyService.disableEnable(main.moduleAlias(), main.metadataId());
            } else if (capability == EntityCapability.SORT) {
                changeSetApplyService.disableSort(main.moduleAlias(), main.metadataId());
            } else if (capability == EntityCapability.TREE) {
                changeSetApplyService.disableTree(main.moduleAlias(), main.metadataId());
            }
            result.remove(capability);
        }
        return result;
    }

    private Set<EntityCapability> requiredBy(DynamicModuleOverviewMode mode, Set<EntityCapability> capabilities) {
        Set<EntityCapability> result = new java.util.LinkedHashSet<>(capabilities);
        if (mode == DynamicModuleOverviewMode.TREE_CARD) {
            result.add(EntityCapability.TREE);
            result.add(EntityCapability.SORT);
        }
        if (mode == DynamicModuleOverviewMode.MICRO_LIST_CARD) result.add(EntityCapability.SORT);
        return result;
    }

    private Set<EntityCapability> configuredCapabilities(PlatformModule module) {
        if (module.getMainCapabilityDeclarations() == null) return Set.of();
        return module.getMainCapabilityDeclarations().stream()
                .map(this::capabilityOrNull)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private EntityCapability capabilityOrNull(String value) {
        try { return EntityCapability.valueOf(value); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private void applyDataScopeSelection(MainMetadata main, Boolean requested) {
        if (requested == null || main.relationId() == null) return;
        if (requested) changeSetApplyService.enableDataScope(main.metadataId());
        else changeSetApplyService.disableDataScope(main.moduleAlias(), main.metadataId());
    }

    private void persistCapabilityIntent(PlatformModule module, Set<EntityCapability> capabilities) {
        module.setMainCapabilityDeclarations(capabilities.stream().map(Enum::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    private void validate(DynamicModuleOverviewMode mode, Set<EntityCapability> capabilities) {
        boolean tree = capabilities.contains(EntityCapability.TREE);
        if (mode == DynamicModuleOverviewMode.TREE_CARD && (!tree || !capabilities.contains(EntityCapability.SORT))) {
            throw new PlatformException("TREE_CARD overview mode requires MAIN metadata TREE and SORT capabilities");
        }
    }

    private DynamicModuleOverviewMode effectiveMode(PlatformModule module) {
        return module.getOverviewMode() == null ? DynamicModuleOverviewMode.LIST_CARD : module.getOverviewMode();
    }
    private record MainMetadata(String moduleAlias, String relationId, String metadataId, Integer metadataVersion,
                                Set<EntityCapability> capabilities) { }
}
