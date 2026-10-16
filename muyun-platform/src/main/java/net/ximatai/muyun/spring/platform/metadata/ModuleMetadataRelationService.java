package net.ximatai.muyun.spring.platform.metadata;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.SortPartition;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.List;
import java.util.Objects;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class ModuleMetadataRelationService extends AbstractAbilityService<ModuleMetadataRelation> implements
        SoftDeleteAbility<ModuleMetadataRelation>,
        SortAbility<ModuleMetadataRelation>,
        QueryAbility<ModuleMetadataRelation> {
    public static final String MODULE_ALIAS = "platform.module_metadata_relation";

    private final PlatformModuleService moduleService;
    private final MetadataService metadataService;
    private final PlatformDynamicRuntimeRefreshCoordinator runtimeRefreshCoordinator;
    private final ObjectProvider<ConfigurationReferenceDeletionGuard> referenceGuardProvider;
    private final ObjectProvider<MetadataFieldService> metadataFieldServiceProvider;
    private final ApplicationEventPublisher eventPublisher;

    public ModuleMetadataRelationService(BaseDao<ModuleMetadataRelation, String> relationDao,
                                         PlatformModuleService moduleService,
                                         MetadataService metadataService,
                                         Optional<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinator,
                                         ObjectProvider<ConfigurationReferenceDeletionGuard> referenceGuardProvider,
                                         ObjectProvider<MetadataFieldService> metadataFieldServiceProvider,
                                         ApplicationEventPublisher eventPublisher) {
        super(MODULE_ALIAS, ModuleMetadataRelation.class, relationDao);
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.moduleService = Objects.requireNonNull(moduleService, "moduleService");
        this.metadataService = Objects.requireNonNull(metadataService, "metadataService");
        this.runtimeRefreshCoordinator = runtimeRefreshCoordinator.orElse(null);
        this.referenceGuardProvider = Objects.requireNonNull(referenceGuardProvider, "referenceGuardProvider");
        this.metadataFieldServiceProvider = Objects.requireNonNull(metadataFieldServiceProvider, "metadataFieldServiceProvider");
    }

    @Override
    public void beforeDelete(String id) {
        ConfigurationReferenceDeletionGuard guard = referenceGuardProvider.getIfAvailable();
        if (guard != null) guard.assertCanDelete(ConfigurationReferenceTarget.MODULE_METADATA_RELATION, id);
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, ModuleMetadataRelation.class, java.util.List.of("id", "moduleAlias", "metadataId", "relationAlias", "relationRole", "parentMetadataId", "foreignKey", "autoPopulate", "title", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"));
    }

    /**
     * Main relations have no parent.  Do not express that as an {@code eq(..., null)}
     * criterion: the database criteria compiler rightfully treats equality parameters as
     * non-null.  Child relations remain ordered inside their owning parent; the sole main
     * relation is ordered inside its module/role partition.
     */
    @Override
    public SortPartition<ModuleMetadataRelation> sortPartition() {
        return new SortPartition<>() {
            @Override
            public Criteria criteriaFor(ModuleMetadataRelation relation) {
                Criteria criteria = Criteria.of()
                        .eq("moduleAlias", relation.getModuleAlias())
                        .eq("relationRole", relation.getRelationRole());
                if (relation.getParentMetadataId() != null) {
                    criteria.eq("parentMetadataId", relation.getParentMetadataId());
                }
                return criteria;
            }

            @Override
            public void requireSamePartition(ModuleMetadataRelation left, ModuleMetadataRelation right) {
                if (!Objects.equals(left.getModuleAlias(), right.getModuleAlias())
                        || left.getRelationRole() != right.getRelationRole()
                        || !Objects.equals(left.getParentMetadataId(), right.getParentMetadataId())) {
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.module-metadata-relation.metadata-relations-can-only-be-reordered-under-the-same-parent-metadata",
                            "Metadata relations can only be reordered under the same parent metadata"));
                }
            }
        };
    }

    @Override
    public List<String> sortPartitionFields() {
        return List.of("moduleAlias", "relationRole", "parentMetadataId");
    }

    @Override
    public void beforeInsert(ModuleMetadataRelation relation) {
        normalizeAndValidate(relation);
    }

    @Override
    public void beforeUpdate(ModuleMetadataRelation relation) {
        normalizeAndValidate(relation);
    }

    @Override
    public void afterChanged(ModuleMetadataRelation relation) {
        eventPublisher.publishEvent(new MetadataChangedEvent(relation.getMetadataId(), relation.getModuleAlias(), relation.getTenantId()));
        if (runtimeRefreshCoordinator != null && !MetadataCapabilityGovernanceMutationContext.isActive()) {
            runtimeRefreshCoordinator.refreshByRelation(relation);
        }
    }

    private void normalizeAndValidate(ModuleMetadataRelation relation) {
        String moduleAlias = PlatformNameRules.requireModuleAlias(relation.getModuleAlias());
        PlatformModule module = moduleService.select(moduleAlias);
        if (module == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.module-metadata-relation.module-metadata-relation-requires-existing-module",
                    "Module metadata relation requires existing module: {value1}"), Map.of("value1", String.valueOf(moduleAlias)));
        }
        Metadata metadata = metadataService.select(relation.getMetadataId());
        if (metadata == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.module-metadata-relation.module-metadata-relation-requires-existing-metadata",
                    "Module metadata relation requires existing metadata: {value1}"), Map.of("value1", String.valueOf(relation.getMetadataId())));
        }
        if (relation.getRelationRole() == null) {
            relation.setRelationRole(RelationRole.MAIN);
        }
        if (relation.getRelationAlias() == null || relation.getRelationAlias().isBlank()) {
            relation.setRelationAlias(metadata.getAlias());
        }
        PlatformNameRules.requireIdentifier(relation.getRelationAlias(), "relationAlias");
        if (relation.getRelationRole() == RelationRole.MAIN) {
            rejectDuplicateMainRelation(relation);
        } else {
            if (Boolean.TRUE.equals(metadata.getDataScopeEnabled())) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.module-metadata-relation.child-metadata-cannot-enable-module-data-scope",
                        "Child metadata cannot enable module data scope: {value1}"), Map.of("value1", String.valueOf(metadata.getAlias())));
            }
            validateChildRelation(relation, metadata);
        }
        rejectDuplicateRelationAlias(relation);
        if (relation.getAutoPopulate() == null) {
            relation.setAutoPopulate(Boolean.FALSE);
        }
        relation.setModuleAlias(moduleAlias);
    }

    private void rejectDuplicateMainRelation(ModuleMetadataRelation relation) {
        rejectDuplicate(relation, Criteria.of()
                        .eq("moduleAlias", relation.getModuleAlias())
                        .eq("relationRole", RelationRole.MAIN), () -> PlatformErrors.badRequestText(TextReference.code(
                                "platform.rejection.module-metadata-relation.module-can-only-have-one-main-metadata-relation",
                                "Module can only have one MAIN metadata relation: {value1}"), Map.of("value1", String.valueOf(relation.getModuleAlias()))));
        relation.setParentMetadataId(null);
        relation.setForeignKey(null);
    }

    private void validateChildRelation(ModuleMetadataRelation relation, Metadata metadata) {
        if (relation.getParentMetadataId() == null || relation.getParentMetadataId().isBlank()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.module-metadata-relation.child-relation-requires-parentmetadataid",
                    "Child relation requires parentMetadataId"));
        }
        if (metadataService.select(relation.getParentMetadataId()) == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.module-metadata-relation.relation-requires-existing-parent-metadata",
                    "Relation requires existing parent metadata: {value1}"), Map.of("value1", String.valueOf(relation.getParentMetadataId())));
        }
        if (count(Criteria.of()
                .eq("moduleAlias", relation.getModuleAlias())
                .eq("metadataId", relation.getParentMetadataId())) <= 0) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.module-metadata-relation.relation-requires-parent-metadata-relation-in-same-module",
                    "Relation requires parent metadata relation in same module: {value1}"), Map.of("value1", String.valueOf(relation.getParentMetadataId())));
        }
        if (relation.getForeignKey() == null || relation.getForeignKey().isBlank()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.module-metadata-relation.child-relation-requires-foreignkey",
                    "Child relation requires foreignKey"));
        }
        PlatformNameRules.requireFieldName(relation.getForeignKey(), "foreignKey");
        MetadataFieldService fieldService = metadataFieldServiceProvider.getIfAvailable();
        if (fieldService != null) {
            MetadataField foreignKey = fieldService.list(Criteria.of()
                            .eq("metadataId", relation.getMetadataId())
                            .eq("fieldName", relation.getForeignKey()),
                    new PageRequest(0, 1)).stream().findFirst().orElse(null);
            if (foreignKey == null) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.module-metadata-relation.child-relation-requires-physical-foreign-key-field",
                        "Child relation requires physical foreign key field: {value1}"), Map.of("value1", String.valueOf(relation.getForeignKey())));
            }
            if (foreignKey.getFieldForm() != MetadataFieldForm.PHYSICAL) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.module-metadata-relation.child-relation-foreign-key-must-be-a-physical-field",
                        "Child relation foreign key must be a physical field: {value1}"), Map.of("value1", String.valueOf(relation.getForeignKey())));
            }
            ModuleMetadataCapabilityPolicy.validateChildMetadata(metadata,
                    fieldService.list(Criteria.of().eq("metadataId", relation.getMetadataId()),
                            new PageRequest(0, Integer.MAX_VALUE)));
        }
    }

    private void rejectDuplicateRelationAlias(ModuleMetadataRelation relation) {
        rejectDuplicate(relation, Criteria.of()
                        .eq("moduleAlias", relation.getModuleAlias())
                        .eq("relationAlias", relation.getRelationAlias()), () -> PlatformErrors.badRequestText(TextReference.code(
                                "platform.rejection.module-metadata-relation.relationalias-must-be-unique-within-module",
                                "relationAlias must be unique within module: {value1}"), Map.of("value1", String.valueOf(relation.getRelationAlias()))));
    }
}
