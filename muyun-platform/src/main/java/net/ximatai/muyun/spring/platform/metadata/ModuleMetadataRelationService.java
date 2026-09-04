package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.SortPartition;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.List;
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

    public ModuleMetadataRelationService(BaseDao<ModuleMetadataRelation, String> relationDao,
                                         PlatformModuleService moduleService,
                                         MetadataService metadataService) {
        this(relationDao, moduleService, metadataService, Optional.empty(), provider(null));
    }

    public ModuleMetadataRelationService(BaseDao<ModuleMetadataRelation, String> relationDao,
                                         PlatformModuleService moduleService,
                                         MetadataService metadataService,
                                         Optional<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinator) {
        this(relationDao, moduleService, metadataService, runtimeRefreshCoordinator, provider(null));
    }

    public ModuleMetadataRelationService(BaseDao<ModuleMetadataRelation, String> relationDao,
                                         PlatformModuleService moduleService,
                                         MetadataService metadataService,
                                         Optional<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinator,
                                         Optional<MetadataFieldService> metadataFieldService) {
        this(relationDao, moduleService, metadataService, runtimeRefreshCoordinator, provider(null),
                provider(metadataFieldService == null ? null : metadataFieldService.orElse(null)));
    }

    public ModuleMetadataRelationService(BaseDao<ModuleMetadataRelation, String> relationDao,
                                         PlatformModuleService moduleService,
                                         MetadataService metadataService,
                                         Optional<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinator,
                                         ObjectProvider<ConfigurationReferenceDeletionGuard> referenceGuardProvider) {
        this(relationDao, moduleService, metadataService, runtimeRefreshCoordinator, referenceGuardProvider, provider(null));
    }

    @Autowired
    public ModuleMetadataRelationService(BaseDao<ModuleMetadataRelation, String> relationDao,
                                         PlatformModuleService moduleService,
                                         MetadataService metadataService,
                                         Optional<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinator,
                                         ObjectProvider<ConfigurationReferenceDeletionGuard> referenceGuardProvider,
                                         ObjectProvider<MetadataFieldService> metadataFieldServiceProvider) {
        super(MODULE_ALIAS, ModuleMetadataRelation.class, relationDao);
        this.moduleService = moduleService;
        this.metadataService = metadataService;
        this.runtimeRefreshCoordinator = runtimeRefreshCoordinator.orElse(null);
        this.referenceGuardProvider = referenceGuardProvider;
        this.metadataFieldServiceProvider = metadataFieldServiceProvider;
    }

    @Override
    public void beforeDelete(String id) {
        ConfigurationReferenceDeletionGuard guard = referenceGuardProvider.getIfAvailable();
        if (guard != null) guard.assertCanDelete(ConfigurationReferenceTarget.MODULE_METADATA_RELATION, id);
    }

    private static <T> ObjectProvider<T> provider(T value) { return new ObjectProvider<>() {
        @Override public T getObject(Object... args) { return value; }
        @Override public T getIfAvailable() { return value; }
        @Override public T getIfUnique() { return value; }
        @Override public T getObject() { return value; }
    }; }

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
                if (!java.util.Objects.equals(left.getModuleAlias(), right.getModuleAlias())
                        || left.getRelationRole() != right.getRelationRole()
                        || !java.util.Objects.equals(left.getParentMetadataId(), right.getParentMetadataId())) {
                    throw new PlatformException("Metadata relations can only be reordered under the same parent metadata");
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
        if (runtimeRefreshCoordinator != null && !MetadataCapabilityGovernanceMutationContext.isActive()) {
            runtimeRefreshCoordinator.refreshByRelation(relation);
        }
    }

    private void normalizeAndValidate(ModuleMetadataRelation relation) {
        String moduleAlias = PlatformNameRules.requireModuleAlias(relation.getModuleAlias());
        PlatformModule module = moduleService.select(moduleAlias);
        if (module == null) {
            throw new PlatformException("Module metadata relation requires existing module: " + moduleAlias);
        }
        Metadata metadata = metadataService.select(relation.getMetadataId());
        if (metadata == null) {
            throw new PlatformException("Module metadata relation requires existing metadata: " + relation.getMetadataId());
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
                throw new PlatformException("Child metadata cannot enable module data scope: " + metadata.getAlias());
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
                        .eq("relationRole", RelationRole.MAIN),
                "Module can only have one MAIN metadata relation: " + relation.getModuleAlias());
        relation.setParentMetadataId(null);
        relation.setForeignKey(null);
    }

    private void validateChildRelation(ModuleMetadataRelation relation, Metadata metadata) {
        if (relation.getParentMetadataId() == null || relation.getParentMetadataId().isBlank()) {
            throw new PlatformException("Child relation requires parentMetadataId");
        }
        if (metadataService.select(relation.getParentMetadataId()) == null) {
            throw new PlatformException("Relation requires existing parent metadata: " + relation.getParentMetadataId());
        }
        if (count(Criteria.of()
                .eq("moduleAlias", relation.getModuleAlias())
                .eq("metadataId", relation.getParentMetadataId())) <= 0) {
            throw new PlatformException("Relation requires parent metadata relation in same module: "
                    + relation.getParentMetadataId());
        }
        if (relation.getForeignKey() == null || relation.getForeignKey().isBlank()) {
            throw new PlatformException("Child relation requires foreignKey");
        }
        PlatformNameRules.requireFieldName(relation.getForeignKey(), "foreignKey");
        MetadataFieldService fieldService = metadataFieldServiceProvider.getIfAvailable();
        if (fieldService != null) {
            MetadataField foreignKey = fieldService.list(Criteria.of()
                            .eq("metadataId", relation.getMetadataId())
                            .eq("fieldName", relation.getForeignKey()),
                    new PageRequest(0, 1)).stream().findFirst().orElse(null);
            if (foreignKey == null) {
                throw new PlatformException("Child relation requires physical foreign key field: "
                        + relation.getForeignKey());
            }
            if (foreignKey.getFieldForm() != MetadataFieldForm.PHYSICAL) {
                throw new PlatformException("Child relation foreign key must be a physical field: "
                        + relation.getForeignKey());
            }
            ModuleMetadataCapabilityPolicy.validateChildMetadata(metadata,
                    fieldService.list(Criteria.of().eq("metadataId", relation.getMetadataId()),
                            new PageRequest(0, Integer.MAX_VALUE)));
        }
    }

    private void rejectDuplicateRelationAlias(ModuleMetadataRelation relation) {
        rejectDuplicate(relation, Criteria.of()
                        .eq("moduleAlias", relation.getModuleAlias())
                        .eq("relationAlias", relation.getRelationAlias()),
                "relationAlias must be unique within module: " + relation.getRelationAlias());
    }
}
