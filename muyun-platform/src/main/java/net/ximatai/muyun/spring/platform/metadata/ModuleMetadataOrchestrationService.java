package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.LinkedHashSet;

/**
 * Module-scoped metadata authoring operations.
 *
 * <p>The service deliberately owns the transaction spanning Metadata and its module relation so
 * callers cannot leave a dynamic module with an unbound half-created entity.</p>
 */
@Service
public class ModuleMetadataOrchestrationService {
    private final PlatformModuleService moduleService;
    private final MetadataService metadataService;
    private final ModuleMetadataRelationService relationService;
    private final MetadataFieldService fieldService;
    private final PlatformMetadataSchemaEnsureService schemaEnsureService;
    private final PlatformDynamicRuntimeRefreshCoordinator refreshCoordinator;

    public ModuleMetadataOrchestrationService(PlatformModuleService moduleService,
                                               MetadataService metadataService,
                                               ModuleMetadataRelationService relationService,
                                               MetadataFieldService fieldService,
                                               PlatformMetadataSchemaEnsureService schemaEnsureService,
                                               PlatformDynamicRuntimeRefreshCoordinator refreshCoordinator) {
        this.moduleService = Objects.requireNonNull(moduleService, "moduleService must not be null");
        this.metadataService = Objects.requireNonNull(metadataService, "metadataService must not be null");
        this.relationService = Objects.requireNonNull(relationService, "relationService must not be null");
        this.fieldService = Objects.requireNonNull(fieldService, "fieldService must not be null");
        this.schemaEnsureService = Objects.requireNonNull(schemaEnsureService, "schemaEnsureService must not be null");
        this.refreshCoordinator = Objects.requireNonNull(refreshCoordinator, "refreshCoordinator must not be null");
    }

    @Transactional
    public ModuleMainMetadataCreationResult createMainMetadata(String moduleAlias,
                                                                ModuleMainMetadataCreateCommand command) {
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        if (command == null) {
            throw new IllegalArgumentException("main metadata create command must not be null");
        }
        PlatformModule module = moduleService.select(validModuleAlias);
        if (module == null) {
            throw new PlatformException("Metadata orchestration requires existing module: " + validModuleAlias);
        }
        if (module.getModuleKind() != ModuleKind.DYNAMIC) {
            throw new PlatformException("Metadata orchestration only supports dynamic module: " + validModuleAlias);
        }
        if (relationService.count(Criteria.of()
                .eq("moduleAlias", validModuleAlias)
                .eq("relationRole", RelationRole.MAIN)) > 0) {
            throw new PlatformException("Module already has MAIN metadata relation: " + validModuleAlias);
        }

        Metadata metadata = new Metadata();
        metadata.setApplicationAlias(module.getApplicationAlias());
        metadata.setAlias(command.alias());
        metadata.setTitle(command.title());
        metadata.setSchemaName(command.schemaName());
        metadata.setTableName(command.tableName());
        java.util.Set<EntityCapability> moduleCapabilities = mainCapabilities(module);
        metadata.setDataScopeEnabled(moduleCapabilities.contains(EntityCapability.DATA_SCOPE)
                || (module.getMainCapabilityDeclarations() == null && Boolean.TRUE.equals(command.dataScopeEnabled())));
        // A newly created dynamic model is governed from its first save. Null is
        // reserved for legacy metadata whose capability state still needs migration.
        metadata.setCapabilityDeclarations(MetadataCapabilityCatalog.declarationNames(moduleCapabilities.stream()
                .filter(MetadataCapabilityCatalog::isDeclarable)
                .collect(java.util.stream.Collectors.toSet())));
        String metadataId = MetadataCapabilityGovernanceMutationContext.run(() -> metadataService.insert(metadata));
        MetadataCapabilityManagedFieldMaterializer.materialize(fieldService, metadata, moduleCapabilities);

        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setModuleAlias(validModuleAlias);
        relation.setMetadataId(metadataId);
        relation.setRelationRole(RelationRole.MAIN);
        relation.setRelationAlias(metadata.getAlias());
        relation.setTitle(metadata.getTitle());
        String relationId = relationService.insert(relation);
        publishCreatedMetadata(metadataService.select(metadataId), validModuleAlias);
        return new ModuleMainMetadataCreationResult(metadataService.select(metadataId), relationService.select(relationId));
    }

    private java.util.Set<EntityCapability> mainCapabilities(PlatformModule module) {
        if (module.getMainCapabilityDeclarations() == null) return java.util.Set.of();
        java.util.EnumSet<EntityCapability> result = java.util.EnumSet.noneOf(EntityCapability.class);
        for (String declaration : module.getMainCapabilityDeclarations()) {
            try {
                result.add(EntityCapability.valueOf(declaration));
            } catch (IllegalArgumentException error) {
                throw new PlatformException("Invalid module MAIN capability declaration: " + declaration);
            }
        }
        return result;
    }

    /**
     * Creates a child entity as one model operation.  The parent foreign key is platform-derived
     * so the relationship can never be persisted without its physical column and declaration.
     */
    @Transactional
    public ModuleMainMetadataCreationResult createChildMetadata(String moduleAlias, String parentRelationId,
                                                                 ModuleChildMetadataCreateCommand command) {
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        if (command == null) throw new IllegalArgumentException("child metadata create command must not be null");
        PlatformModule module = requireDynamicModule(validModuleAlias);
        ModuleMetadataRelation parentRelation = relationService.select(parentRelationId);
        if (parentRelation == null || !validModuleAlias.equals(parentRelation.getModuleAlias())) {
            throw new PlatformException("Child metadata requires a parent node in the current module: " + parentRelationId);
        }
        Metadata parentMetadata = metadataService.select(parentRelation.getMetadataId());
        if (parentMetadata == null) {
            throw new PlatformException("Child metadata parent no longer exists: " + parentRelation.getMetadataId());
        }

        Metadata child = new Metadata();
        child.setApplicationAlias(module.getApplicationAlias());
        child.setAlias(command.alias());
        child.setTitle(command.title());
        child.setSchemaName(command.schemaName());
        child.setTableName(command.tableName());
        child.setDataScopeEnabled(Boolean.FALSE);
        child.setCapabilityDeclarations(new LinkedHashSet<>());
        String childMetadataId = MetadataCapabilityGovernanceMutationContext.run(() -> metadataService.insert(child));
        MetadataCapabilityManagedFieldMaterializer.materialize(fieldService, child, java.util.Set.of());

        MetadataField parentForeignKey = new MetadataField();
        parentForeignKey.setMetadataId(childMetadataId);
        parentForeignKey.setFieldName(childForeignKeyName(parentMetadata.getAlias()));
        parentForeignKey.setColumnName(parentMetadata.getAlias() + "_id");
        parentForeignKey.setFieldSpecAlias("string");
        parentForeignKey.setFieldOwnership(MetadataFieldOwnership.BUSINESS);
        parentForeignKey.setFieldForm(MetadataFieldForm.PHYSICAL);
        parentForeignKey.setRequired(Boolean.TRUE);
        parentForeignKey.setIndexed(Boolean.TRUE);
        parentForeignKey.setTitle(parentMetadata.getTitle() + " ID");
        parentForeignKey.setEnabled(Boolean.TRUE);
        fieldService.insert(parentForeignKey);

        ModuleMetadataRelation childRelation = new ModuleMetadataRelation();
        childRelation.setModuleAlias(validModuleAlias);
        childRelation.setMetadataId(childMetadataId);
        childRelation.setRelationRole(RelationRole.CHILD);
        childRelation.setParentMetadataId(parentMetadata.getId());
        childRelation.setForeignKey(parentForeignKey.getFieldName());
        childRelation.setRelationAlias(child.getAlias());
        childRelation.setTitle(child.getTitle());
        String childRelationId = relationService.insert(childRelation);
        publishCreatedMetadata(metadataService.select(childMetadataId), validModuleAlias);
        return new ModuleMainMetadataCreationResult(metadataService.select(childMetadataId),
                relationService.select(childRelationId));
    }

    /** Publishes model creation through the same schema-then-post-commit-runtime boundary as change sets. */
    private void publishCreatedMetadata(Metadata metadata, String moduleAlias) {
        if (metadata == null) throw new PlatformException("Created metadata cannot be reloaded for publication");
        schemaEnsureService.ensureNow(metadata);
        TransactionScopeSupport.afterCommitOrNow(() -> refreshCoordinator.activateModulesNow(java.util.List.of(moduleAlias)));
    }

    private PlatformModule requireDynamicModule(String moduleAlias) {
        PlatformModule module = moduleService.select(moduleAlias);
        if (module == null) {
            throw new PlatformException("Metadata orchestration requires existing module: " + moduleAlias);
        }
        if (module.getModuleKind() != ModuleKind.DYNAMIC) {
            throw new PlatformException("Metadata orchestration only supports dynamic module: " + moduleAlias);
        }
        return module;
    }

    private String childForeignKeyName(String parentAlias) {
        StringBuilder result = new StringBuilder();
        boolean uppercaseNext = false;
        for (char character : parentAlias.toCharArray()) {
            if (character == '_') {
                uppercaseNext = true;
            } else if (uppercaseNext) {
                result.append(Character.toUpperCase(character));
                uppercaseNext = false;
            } else {
                result.append(character);
            }
        }
        return result.append("Id").toString();
    }
}
