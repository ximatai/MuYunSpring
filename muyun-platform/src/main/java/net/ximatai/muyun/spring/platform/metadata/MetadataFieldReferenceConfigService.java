package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceIntegrityPolicy;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceProjection;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargets;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class MetadataFieldReferenceConfigService extends AbstractAbilityService<MetadataFieldReferenceConfig> implements
        SoftDeleteAbility<MetadataFieldReferenceConfig>,
        QueryAbility<MetadataFieldReferenceConfig> {
    public static final String MODULE_ALIAS = "platform.metadata_field_reference_config";
    private static final Set<String> STANDARD_FIELDS = Set.copyOf(StandardEntitySchema.fieldNames());

    private final MetadataFieldService fieldService;
    private final MetadataService metadataService;
    private final FieldSpecService fieldTypeService;
    private final PlatformModuleService moduleService;
    private final ModuleMetadataRelationService relationService;
    private final Optional<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinator;

    public MetadataFieldReferenceConfigService(BaseDao<MetadataFieldReferenceConfig, String> referenceConfigDao,
                                               MetadataFieldService fieldService,
                                               MetadataService metadataService,
                                               FieldSpecService fieldTypeService,
                                               PlatformModuleService moduleService,
                                               ModuleMetadataRelationService relationService) {
        this(referenceConfigDao, fieldService, metadataService, fieldTypeService, moduleService, relationService,
                Optional.empty());
    }

    @Autowired
    public MetadataFieldReferenceConfigService(BaseDao<MetadataFieldReferenceConfig, String> referenceConfigDao,
                                               MetadataFieldService fieldService,
                                               MetadataService metadataService,
                                               FieldSpecService fieldTypeService,
                                               PlatformModuleService moduleService,
                                               ModuleMetadataRelationService relationService,
                                               Optional<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinator) {
        super(MODULE_ALIAS, MetadataFieldReferenceConfig.class, referenceConfigDao);
        this.fieldService = fieldService;
        this.metadataService = metadataService;
        this.fieldTypeService = fieldTypeService;
        this.moduleService = moduleService;
        this.relationService = relationService;
        this.runtimeRefreshCoordinator = Objects.requireNonNull(runtimeRefreshCoordinator,
                "runtimeRefreshCoordinator must not be null");
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, MetadataFieldReferenceConfig.class, java.util.List.of("id", "metadataFieldId", "relationId", "targetModuleAlias", "targetMetadataId", "targetEntityAlias", "cardinality", "targetUnavailablePolicy", "projectionMappings", "createdAt", "updatedAt"));
    }

    @Override
    public void beforeInsert(MetadataFieldReferenceConfig config) {
        normalizeAndValidate(config);
    }

    @Override
    public void beforeUpdate(MetadataFieldReferenceConfig config) {
        normalizeAndValidate(config);
    }

    @Override
    public void afterChanged(MetadataFieldReferenceConfig config) {
        refreshByMetadataFieldId(config.getMetadataFieldId());
    }

    public MetadataFieldReferenceConfig findByMetadataFieldId(String metadataFieldId) {
        if (metadataFieldId == null || metadataFieldId.isBlank()) {
            return null;
        }
        return findOne(Criteria.of()
                .eq("metadataFieldId", metadataFieldId)
                .isNull("relationId"));
    }

    public MetadataFieldReferenceConfig findForRelation(String metadataFieldId, String relationId) {
        if (metadataFieldId == null || metadataFieldId.isBlank()) {
            return null;
        }
        if (relationId != null && !relationId.isBlank()) {
            MetadataFieldReferenceConfig override = findOne(Criteria.of()
                    .eq("metadataFieldId", metadataFieldId)
                    .eq("relationId", relationId));
            if (override != null) {
                return override;
            }
        }
        return findByMetadataFieldId(metadataFieldId);
    }

    private void normalizeAndValidate(MetadataFieldReferenceConfig config) {
        MetadataField sourceField = requireField(config.getMetadataFieldId(), "source metadata field");
        ModuleMetadataRelation sourceRelation = normalizeRelation(config, sourceField);
        FieldSpec sourceType = fieldTypeService.requireFieldType(sourceField.getFieldSpecAlias());
        if (sourceType.getFieldType() != FieldType.STRING && sourceType.getFieldType() != FieldType.TEXT) {
            throw new IllegalArgumentException("reference source field must be string/text: " + sourceField.getFieldName());
        }
        validateChildForeignKeyReference(sourceField, config);
        ReferenceTarget target = resolveTarget(config, sourceField, sourceRelation);
        if (config.getCardinality() == null) {
            config.setCardinality(ReferenceCardinality.ONE);
        }
        if (config.getTargetUnavailablePolicy() == null) {
            config.setTargetUnavailablePolicy(ReferenceTargetUnavailablePolicy.PRESERVE_HISTORY);
        }
        new ReferencePlan(sourceField.getFieldName(), target,
                config.getCardinality(), List.of(),
                new ReferenceIntegrityPolicy(config.getTargetUnavailablePolicy()));
        if (!config.targetsStaticEntity()
                && config.getTargetModuleAlias() != null
                && !config.projections().isEmpty()) {
            throw new PlatformException("Cross-module reference display is not supported yet: "
                    + config.getTargetModuleAlias());
        }
        rejectDuplicate(config, scopeCriteria(config.getMetadataFieldId(), config.getRelationId()),
                "metadata field reference config must be unique in scope: " + config.getMetadataFieldId());
    }

    /**
     * Revalidates and resolves the persisted target into the source-neutral runtime identity used
     * by dynamic module compilation.  A static target deliberately has no metadata binding.
     */
    public ReferenceTarget resolveTarget(MetadataFieldReferenceConfig config,
                                          MetadataField sourceField,
                                          ModuleMetadataRelation sourceRelation) {
        normalizeTargetIdentifiers(config);
        if (config.targetsStaticEntity()) {
            ReferenceTarget target = resolveStaticTarget(config, sourceRelation);
            validateOutputFields(config, sourceField.getMetadataId(), target);
            return target;
        }
        ReferenceTarget target = resolveDynamicTarget(config, sourceField, sourceRelation);
        validateOutputFields(config, sourceField.getMetadataId(), target);
        return target;
    }

    private void normalizeTargetIdentifiers(MetadataFieldReferenceConfig config) {
        config.setTargetMetadataId(normalizeBlank(config.getTargetMetadataId()));
        config.setTargetEntityAlias(normalizeBlank(config.getTargetEntityAlias()));
        String targetModuleAlias = normalizeBlank(config.getTargetModuleAlias());
        if (targetModuleAlias == null) {
            config.setTargetModuleAlias(null);
            return;
        }
        targetModuleAlias = PlatformNameRules.requireModuleAlias(targetModuleAlias);
        if (moduleService.select(targetModuleAlias) == null) {
            throw new PlatformException("Reference config requires existing target module: " + targetModuleAlias);
        }
        config.setTargetModuleAlias(targetModuleAlias);
    }

    private ReferenceTarget resolveStaticTarget(MetadataFieldReferenceConfig config,
                                                 ModuleMetadataRelation sourceRelation) {
        if (config.getTargetMetadataId() != null) {
            throw new PlatformException("Static reference target cannot declare target metadata: "
                    + config.getTargetMetadataId());
        }
        if (sourceRelation == null) {
            throw new PlatformException("Static reference config must be relation-scoped: "
                    + config.getMetadataFieldId());
        }
        String targetModuleAlias = config.getTargetModuleAlias();
        if (targetModuleAlias == null) {
            throw new PlatformException("Static reference config requires target module: "
                    + config.getMetadataFieldId());
        }
        PlatformModule targetPlatformModule = moduleService.select(targetModuleAlias);
        if (targetPlatformModule == null || targetPlatformModule.getModuleKind() != ModuleKind.STATIC) {
            throw new PlatformException("Static reference config requires STATIC target module: " + targetModuleAlias);
        }
        String entityAlias = PlatformNameRules.requireIdentifier(config.getTargetEntityAlias(), "targetEntityAlias");
        ReferenceTarget target = ReferenceTargets.fromModuleAlias(targetModuleAlias);
        if (!entityAlias.equals(target.entityAlias())) {
            throw new PlatformException("Static reference entity alias must match target platform module: "
                    + targetModuleAlias + "." + entityAlias);
        }
        ReferenceAbility<?> ability = PlatformAbilityRuntime.referenceTargetResolver().resolve(target)
                .orElseThrow(() -> new PlatformException("Static reference target is not registered: "
                        + target.qualifiedName()));
        if (!target.equals(ReferenceTargets.of(ability))) {
            throw new PlatformException("Static reference target identity mismatch: " + target.qualifiedName());
        }
        return target;
    }

    private ReferenceTarget resolveDynamicTarget(MetadataFieldReferenceConfig config,
                                                  MetadataField sourceField,
                                                  ModuleMetadataRelation sourceRelation) {
        if (config.getTargetMetadataId() == null) {
            throw new PlatformException("Reference config requires target metadata or static target entity: "
                    + config.getMetadataFieldId());
        }
        Metadata targetMetadata = metadataService.select(config.getTargetMetadataId());
        if (targetMetadata == null) {
            throw new PlatformException("Reference config requires existing target metadata: " + config.getTargetMetadataId());
        }
        validateCrossModuleTarget(config, sourceRelation);
        validateTargetBinding(config, sourceField, sourceRelation);
        String targetModuleAlias = config.getTargetModuleAlias() == null
                ? sourceRelation == null ? null : sourceRelation.getModuleAlias()
                : config.getTargetModuleAlias();
        if (targetModuleAlias == null) {
            // A metadata-level default can be authored before it is bound to a module.  The
            // compiler always supplies a relation and resolves the concrete target then.
            return ReferenceTarget.of("platform", "reference_target");
        }
        return ReferenceTarget.of(targetModuleAlias, targetMetadata.getAlias());
    }

    private String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void validateChildForeignKeyReference(MetadataField sourceField,
                                                   MetadataFieldReferenceConfig config) {
        relationService.list(Criteria.of().eq("metadataId", sourceField.getMetadataId())
                        .eq("relationRole", RelationRole.CHILD),
                new net.ximatai.muyun.database.core.orm.PageRequest(0, Integer.MAX_VALUE))
                .stream()
                .filter(relation -> config.getRelationId() == null || config.getRelationId().equals(relation.getId()))
                .forEach(relation -> {
                    if (!relation.getMetadataId().equals(sourceField.getMetadataId())
                            || !relation.getForeignKey().equals(sourceField.getFieldName())) {
                        return;
                    }
                    if (config.targetsStaticEntity()) {
                        throw new PlatformException("Child relation foreign key cannot reference a static target: "
                                + relation.getRelationAlias());
                    }
                    ModuleMetadataCapabilityPolicy.validateChildForeignKeyReference(relation, sourceField, config);
                });
    }

    private Criteria scopeCriteria(String metadataFieldId, String relationId) {
        Criteria criteria = Criteria.of().eq("metadataFieldId", metadataFieldId);
        if (relationId == null || relationId.isBlank()) {
            return criteria.isNull("relationId");
        }
        return criteria.eq("relationId", relationId);
    }

    private void refreshByMetadataFieldId(String metadataFieldId) {
        if (runtimeRefreshCoordinator.isEmpty() || metadataFieldId == null || metadataFieldId.isBlank()) {
            return;
        }
        MetadataField field = fieldService.select(metadataFieldId);
        if (field != null) {
            runtimeRefreshCoordinator.get().refreshByMetadataField(field);
        }
    }

    private ModuleMetadataRelation normalizeRelation(MetadataFieldReferenceConfig config, MetadataField field) {
        if (config.getRelationId() == null || config.getRelationId().isBlank()) {
            config.setRelationId(null);
            return null;
        }
        ModuleMetadataRelation relation = relationService.select(config.getRelationId());
        if (relation == null) {
            throw new PlatformException("Reference config requires existing relation: " + config.getRelationId());
        }
        if (!field.getMetadataId().equals(relation.getMetadataId())) {
            throw new PlatformException("Reference config relation metadata mismatch: " + config.getRelationId());
        }
        return relation;
    }

    private void validateCrossModuleTarget(MetadataFieldReferenceConfig config,
                                           ModuleMetadataRelation sourceRelation) {
        String targetModuleAlias = config.getTargetModuleAlias();
        if (targetModuleAlias == null || targetModuleAlias.isBlank()) {
            return;
        }
        if (sourceRelation == null) {
            throw new PlatformException("Cross-module reference config must be relation-scoped: "
                    + config.getMetadataFieldId());
        }
        if (targetModuleAlias.equals(sourceRelation.getModuleAlias())) {
            config.setTargetModuleAlias(null);
            return;
        }
        boolean targetIsMainMetadata = relationService.count(Criteria.of()
                .eq("moduleAlias", targetModuleAlias)
                .eq("metadataId", config.getTargetMetadataId())
                .eq("relationRole", RelationRole.MAIN)) > 0;
        if (!targetIsMainMetadata) {
            throw new PlatformException("Cross-module reference target must be the target module MAIN metadata: "
                    + targetModuleAlias);
        }
    }

    /**
     * A default reference is shared by every relation using its source metadata, so its target
     * must be bound in every one of those module contexts before the configuration is persisted.
     */
    private void validateTargetBinding(MetadataFieldReferenceConfig config,
                                       MetadataField sourceField,
                                       ModuleMetadataRelation sourceRelation) {
        List<ModuleMetadataRelation> sourceRelations = sourceRelation == null
                ? relationService.list(Criteria.of().eq("metadataId", sourceField.getMetadataId()),
                        new net.ximatai.muyun.database.core.orm.PageRequest(0, Integer.MAX_VALUE))
                : List.of(sourceRelation);
        for (ModuleMetadataRelation relation : sourceRelations) {
            String targetModuleAlias = config.getTargetModuleAlias() == null
                    ? relation.getModuleAlias()
                    : config.getTargetModuleAlias();
            if (relationService.count(Criteria.of()
                    .eq("moduleAlias", targetModuleAlias)
                    .eq("metadataId", config.getTargetMetadataId())) <= 0) {
                throw new PlatformException("Reference target metadata is not bound to module: "
                        + targetModuleAlias + "." + config.getTargetMetadataId());
            }
        }
    }

    private void validateOutputFields(MetadataFieldReferenceConfig config,
                                      String sourceMetadataId,
                                      ReferenceTarget target) {
        LinkedHashSet<String> outputFields = new LinkedHashSet<>();
        for (ReferenceProjection projection : config.projections()) {
            PlatformNameRules.requireFieldName(projection.targetField(), "projection.targetField");
            PlatformNameRules.requireFieldName(projection.outputField(), "projection.outputField");
            requireTargetField(config, target, projection.targetField());
            requireAvailableOutputField(sourceMetadataId, projection.outputField(), "reference projection output field");
            if (!outputFields.add(projection.outputField())) {
                throw new PlatformException("Duplicate reference output field: " + projection.outputField());
            }
        }
    }

    private void requireTargetField(MetadataFieldReferenceConfig config,
                                    ReferenceTarget target,
                                    String targetFieldName) {
        if (config.targetsStaticEntity()) {
            ReferenceAbility<?> ability = PlatformAbilityRuntime.referenceTargetResolver().resolve(target)
                    .orElseThrow(() -> new PlatformException("Static reference target is not registered: "
                            + target.qualifiedName()));
            StaticReferenceResolver.requireReadableField(ability.modelClass(), targetFieldName,
                    "reference projection target");
            return;
        }
        if (fieldService.count(Criteria.of()
                .eq("metadataId", config.getTargetMetadataId())
                .eq("fieldName", targetFieldName)) <= 0) {
            throw new PlatformException("Reference projection requires existing target field: " + targetFieldName);
        }
    }

    private void requireAvailableOutputField(String sourceMetadataId, String outputField, String name) {
        if (STANDARD_FIELDS.contains(outputField)
                || fieldService.count(Criteria.of()
                .eq("metadataId", sourceMetadataId)
                .eq("fieldName", outputField)) > 0) {
            throw new PlatformException(name + " conflicts with source field: " + outputField);
        }
    }

    private MetadataField requireField(String metadataFieldId, String name) {
        MetadataField field = metadataFieldId == null || metadataFieldId.isBlank() ? null : fieldService.select(metadataFieldId);
        if (field == null) {
            throw new PlatformException("Reference config requires existing " + name + ": " + metadataFieldId);
        }
        return field;
    }

}
