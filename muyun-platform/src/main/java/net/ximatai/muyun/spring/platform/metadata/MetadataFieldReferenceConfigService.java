package net.ximatai.muyun.spring.platform.metadata;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.PageRequests;
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
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;

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
    private final ObjectProvider<ModuleMetadataFieldService> moduleFields;
    private final Optional<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinator;

    public MetadataFieldReferenceConfigService(BaseDao<MetadataFieldReferenceConfig, String> referenceConfigDao,
                                               MetadataFieldService fieldService,
                                               MetadataService metadataService,
                                               FieldSpecService fieldTypeService,
                                               PlatformModuleService moduleService,
                                               ModuleMetadataRelationService relationService,
                                               ObjectProvider<ModuleMetadataFieldService> moduleFields,
                                               Optional<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinator) {
        super(MODULE_ALIAS, MetadataFieldReferenceConfig.class, referenceConfigDao);
        this.fieldService = fieldService;
        this.metadataService = metadataService;
        this.fieldTypeService = fieldTypeService;
        this.moduleService = moduleService;
        this.relationService = relationService;
        this.moduleFields = Objects.requireNonNull(moduleFields, "moduleFields must not be null");
        this.runtimeRefreshCoordinator = Objects.requireNonNull(runtimeRefreshCoordinator,
                "runtimeRefreshCoordinator must not be null");
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, MetadataFieldReferenceConfig.class, java.util.List.of("id", "metadataFieldId", "relationId", "targetModuleAlias", "targetMetadataId", "targetKeyField", "targetLabelField", "cardinality", "targetUnavailablePolicy", "requireEnabled", "projectionMappings", "affectMappings", "createdAt", "updatedAt"));
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
        if (!MetadataFieldPropertyMutationContext.active()) {
            refreshByMetadataFieldId(config.getMetadataFieldId());
        }
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

    /** Resolves encoded selection mappings to field identities before destructive metadata changes. */
    Optional<String> findAffectReferenceId(String fieldId) {
        MetadataField field = fieldService.select(fieldId);
        if (field == null) return Optional.empty();
        for (MetadataFieldReferenceConfig config : list(Criteria.of().isNotNull("affectMappings"), PageRequests.all())) {
            MetadataField owner = fieldService.select(config.getMetadataFieldId());
            boolean sourceEntity = Objects.equals(field.getMetadataId(), config.getTargetMetadataId());
            boolean destinationEntity = owner != null && Objects.equals(field.getMetadataId(), owner.getMetadataId());
            if (config.affects().stream().anyMatch(mapping ->
                    sourceEntity && field.getFieldName().equals(mapping.referenceField())
                    || destinationEntity && field.getFieldName().equals(mapping.targetField()))) {
                return Optional.of(config.getId());
            }
        }
        return Optional.empty();
    }

    /** A later field specification edit must not invalidate an already published selection mapping. */
    public void validateAffectFieldChange(MetadataField existing, MetadataField proposed) {
        if (existing == null) return;
        FieldType proposedType = fieldTypeService.requireFieldType(proposed.getFieldSpecAlias()).getFieldType();
        if (Objects.equals(existing.getFieldName(), proposed.getFieldName())
                && Objects.equals(existing.getMetadataId(), proposed.getMetadataId())
                && fieldTypeService.requireFieldType(existing.getFieldSpecAlias()).getFieldType() == proposedType) return;
        for (var config : list(Criteria.of().isNotNull("affectMappings"), PageRequests.all())) {
            MetadataField owner = fieldService.select(config.getMetadataFieldId());
            if (owner == null) continue;
            for (var mapping : config.affects()) {
                boolean changesSource = Objects.equals(existing.getMetadataId(), config.getTargetMetadataId())
                        && existing.getFieldName().equals(mapping.referenceField());
                boolean changesDestination = Objects.equals(existing.getMetadataId(), owner.getMetadataId())
                        && existing.getFieldName().equals(mapping.targetField());
                if (!changesSource && !changesDestination) continue;
                if (!Objects.equals(existing.getFieldName(), proposed.getFieldName())
                        || !Objects.equals(existing.getMetadataId(), proposed.getMetadataId())) {
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.metadata-field-reference-config.the-field-is-still-used-by-selection-backfill-remove-the-mapping-first",
                            "字段仍被选择回填占用，请先移除映射：{value1}"), Map.of("value1", String.valueOf(existing.getFieldName())));
                }
                ReferenceTarget target = targetsStaticEntity(config)
                        ? ReferenceTargets.fromModuleAlias(config.getTargetModuleAlias()) : null;
                FieldType sourceType = changesSource ? proposedType : affectSourceType(config, target, mapping.referenceField());
                MetadataField destination = fieldService.list(Criteria.of().eq("metadataId", owner.getMetadataId())
                        .eq("fieldName", mapping.targetField()), PageRequests.all()).getFirst();
                FieldType destinationType = changesDestination ? proposedType
                        : fieldTypeService.requireFieldType(destination.getFieldSpecAlias()).getFieldType();
                requireAffectAssignment(sourceType, destinationType, mapping.referenceField(), mapping.targetField());
            }
        }
    }

    private void normalizeAndValidate(MetadataFieldReferenceConfig config) {
        MetadataField sourceField = requireField(config.getMetadataFieldId(), "source metadata field");
        ModuleMetadataRelation sourceRelation = normalizeRelation(config, sourceField);
        validateDraft(config, sourceField, sourceRelation);
        rejectDuplicate(config, scopeCriteria(config.getMetadataFieldId(), config.getRelationId()), () -> PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.metadata-field-reference-config.metadata-field-reference-config-must-be-unique-in-scope",
                "metadata field reference config must be unique in scope: {value1}"), Map.of("value1", String.valueOf(config.getMetadataFieldId()))));
    }

    /**
     * Validates a proposed reference without requiring its source field or config to be persisted.
     * Change-set preview uses this same target and projection contract before atomic publish.
     */
    public void validateDraft(MetadataFieldReferenceConfig config,
                              MetadataField sourceField,
                              ModuleMetadataRelation sourceRelation) {
        if (config == null) throw new IllegalArgumentException("reference config must not be null");
        if (sourceField == null) throw new IllegalArgumentException("reference source field must not be null");
        if (sourceRelation != null) config.setRelationId(sourceRelation.getId());
        normalizeTargetIdentifiers(config);
        config.setTargetKeyField(normalizeFieldName(config.getTargetKeyField(), "targetKeyField"));
        config.setTargetLabelField(normalizeFieldName(config.getTargetLabelField(), "targetLabelField"));
        String subtitle = normalizeBlank(config.getCandidateSubtitleProjection());
        config.setCandidateSubtitleProjection(subtitle == null ? null
                : new net.ximatai.muyun.spring.ability.reference.ReferenceSelectionProjection(subtitle).key());
        FieldSpec sourceType = fieldTypeService.requireFieldType(sourceField.getFieldSpecAlias());
        if (sourceType.getFieldType() != FieldType.STRING && sourceType.getFieldType() != FieldType.TEXT) {
            throw new IllegalArgumentException("reference source field must be string/text: " + sourceField.getFieldName());
        }
        validateCodeFieldExclusion(sourceField, sourceRelation);
        validateChildForeignKeyReference(sourceField, config);
        ReferenceTarget target = resolveTarget(config, sourceField, sourceRelation);
        if (config.getCardinality() == null) {
            config.setCardinality(ReferenceCardinality.ONE);
        }
        if (config.getCardinality() == ReferenceCardinality.MANY) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field-reference-config.metadata-field-reference-currently-supports-one-cardinality-only",
                    "Metadata field reference currently supports ONE cardinality only: {value1}"), Map.of("value1", String.valueOf(sourceField.getFieldName())));
        }
        validateTargetKey(config, target);
        if (config.getTargetUnavailablePolicy() == null) {
            config.setTargetUnavailablePolicy(ReferenceTargetUnavailablePolicy.PRESERVE_HISTORY);
        }
        config.setRequireEnabled(Boolean.TRUE.equals(config.getRequireEnabled()));
        new ReferencePlan(sourceField.getFieldName(), target,
                config.getCardinality(), List.of(),
                new ReferenceIntegrityPolicy(config.getTargetUnavailablePolicy(), Boolean.TRUE.equals(config.getRequireEnabled())));
    }

    /**
     * Revalidates and resolves the persisted target into the source-neutral runtime identity used
     * by dynamic module compilation.  A static target deliberately has no metadata binding.
     */
    public ReferenceTarget resolveTarget(MetadataFieldReferenceConfig config,
                                          MetadataField sourceField,
                                          ModuleMetadataRelation sourceRelation) {
        normalizeTargetIdentifiers(config);
        if (targetsStaticEntity(config)) {
            ReferenceTarget target = resolveStaticTarget(config, sourceRelation);
            validateTargetIntegrity(config, target);
            normalizeStandardLabelProjection(config, sourceField, target);
            validateOutputFields(config, sourceField.getMetadataId(), target);
            validateAffects(config, sourceField, target);
            validateCandidateSubtitle(config, sourceField, sourceRelation, target);
            return target;
        }
        ReferenceTarget target = resolveDynamicTarget(config, sourceField, sourceRelation);
        validateTargetIntegrity(config, target);
        normalizeStandardLabelProjection(config, sourceField, target);
        validateOutputFields(config, sourceField.getMetadataId(), target);
        validateAffects(config, sourceField, target);
        validateCandidateSubtitle(config, sourceField, sourceRelation, target);
        return target;
    }

    /** Validate against persisted dynamic declarations and Java reference contracts, not business data. */
    private void validateCandidateSubtitle(MetadataFieldReferenceConfig config, MetadataField sourceField,
                                            ModuleMetadataRelation sourceRelation, ReferenceTarget target) {
        if (config.getCandidateSubtitleProjection() == null || config.getCandidateSubtitleProjection().isBlank()) return;
        var projection = new net.ximatai.muyun.spring.ability.reference.ReferenceSelectionProjection(
                config.getCandidateSubtitleProjection());
        ReferenceTarget current = target;
        String metadataId = config.getTargetMetadataId();
        for (int index = 0; index < projection.path().size(); index++) {
            String name = projection.path().get(index);
            boolean terminal = index == projection.path().size() - 1;
            if (metadataId == null) {
                var resolver = PlatformAbilityRuntime.referenceTargetResolver();
                ReferenceAbility<?> ability = resolver.resolve(current).orElseThrow(() ->
                        new IllegalArgumentException("candidate subtitle target is unavailable"));
                StaticReferenceResolver.requireReadableField(ability.modelClass(), name, "candidate subtitle");
                if (terminal) return;
                ReferencePlan hop = resolver.referencePlan(current, name).orElseThrow(() ->
                        new IllegalArgumentException("candidate subtitle hop is not a declared reference: " + name));
                if (hop.cardinality() != ReferenceCardinality.ONE)
                    throw new IllegalArgumentException("candidate subtitle hop requires cardinality ONE: " + name);
                current = hop.target();
                metadataId = metadataIdFor(current);
                continue;
            }
            MetadataField field = fieldService.list(Criteria.of().eq("metadataId", metadataId)
                    .eq("fieldName", name), PageRequests.all()).stream().findFirst().orElse(null);
            boolean pendingField = Objects.equals(metadataId, sourceField.getMetadataId())
                    && name.equals(sourceField.getFieldName());
            if (pendingField) field = sourceField;
            if (field == null && !(terminal && STANDARD_FIELDS.contains(name)))
                throw new IllegalArgumentException("candidate subtitle field does not exist: " + name);
            if (terminal) return;
            ModuleMetadataRelation relation = relationService.list(Criteria.of().eq("moduleAlias", current.moduleAlias())
                    .eq("metadataId", metadataId), PageRequests.all()).stream().findFirst().orElse(null);
            if (sourceRelation != null && sourceRelation.getModuleAlias().equals(current.moduleAlias())
                    && sourceRelation.getMetadataId().equals(metadataId)) relation = sourceRelation;
            String fieldId = field.getId();
            ModuleMetadataField override = relation == null ? null : moduleFields.getObject().listByRelationId(relation.getId())
                    .stream().filter(item -> fieldId != null && fieldId.equals(item.getMetadataFieldId()))
                    .filter(item -> item.getReferenceModuleAlias() != null && !item.getReferenceModuleAlias().isBlank())
                    .findFirst().orElse(null);
            if (override != null) {
                String module = override.getReferenceModuleAlias();
                ModuleMetadataRelation main = relationService.list(Criteria.of().eq("moduleAlias", module)
                        .eq("relationRole", RelationRole.MAIN), PageRequests.all()).stream().findFirst().orElse(null);
                metadataId = main == null ? null : main.getMetadataId();
                current = main == null ? ReferenceTargets.fromModuleAlias(module)
                        : ReferenceTarget.of(module, metadataService.select(metadataId).getAlias());
                continue;
            }
            MetadataFieldReferenceConfig hop = findForRelation(field.getId(), relation == null ? null : relation.getId());
            if (pendingField && (config.getRelationId() == null
                    ? hop == null || hop.getRelationId() == null
                    : relation != null && config.getRelationId().equals(relation.getId()))) hop = config;
            if (hop == null)
                throw new IllegalArgumentException("candidate subtitle hop is not a declared reference: " + name);
            if (hop.getCardinality() == ReferenceCardinality.MANY)
                throw new IllegalArgumentException("candidate subtitle hop requires cardinality ONE: " + name);
            String module = hop.getTargetModuleAlias() == null ? current.moduleAlias() : hop.getTargetModuleAlias();
            metadataId = hop.getTargetMetadataId();
            current = metadataId == null ? ReferenceTargets.fromModuleAlias(module)
                    : ReferenceTarget.of(module, metadataService.select(metadataId).getAlias());
        }
    }

    private String metadataIdFor(ReferenceTarget target) {
        return relationService.list(Criteria.of().eq("moduleAlias", target.moduleAlias()), PageRequests.all()).stream()
                .filter(relation -> {
                    Metadata metadata = metadataService.select(relation.getMetadataId());
                    return metadata != null && metadata.getAlias().equals(target.entityAlias());
                }).map(ModuleMetadataRelation::getMetadataId).findFirst().orElse(null);
    }

    private void validateTargetIntegrity(MetadataFieldReferenceConfig config, ReferenceTarget target) {
        if (!Boolean.TRUE.equals(config.getRequireEnabled())) return;
        boolean enabled;
        if (targetsStaticEntity(config)) {
            enabled = PlatformAbilityRuntime.referenceTargetResolver().resolve(target)
                    .orElseThrow(() -> PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.metadata-field-reference-config.static-reference-target-is-not-registered",
                            "Static reference target is not registered: {value1}"), Map.of("value1", String.valueOf(target.qualifiedName())))).supportsEnabledState();
        } else {
            enabled = MetadataCapabilityCatalog.resolve(metadataService.select(config.getTargetMetadataId()), null,
                    fieldService.list(Criteria.of().eq("metadataId", config.getTargetMetadataId()), PageRequests.all()))
                    .capabilities().contains(net.ximatai.muyun.spring.common.platform.EntityCapability.ENABLE);
        }
        new ReferenceIntegrityPolicy(config.getTargetUnavailablePolicy(), true).validateTarget(target, enabled);
    }

    /**
     * Every reference owns one predictable companion label projection.  The target label may
     * still use the target's default title contract; only the source output name is standardized.
     * Explicit mappings remain additive and are never used as a hidden replacement for it.
     */
    private void normalizeStandardLabelProjection(MetadataFieldReferenceConfig config,
                                                   MetadataField sourceField,
                                                   ReferenceTarget target) {
        String output = sourceField.getFieldName() + "Title";
        String label = config.getTargetLabelField();
        if (label == null || label.isBlank()) {
            label = defaultTargetLabelField(config, target);
        }
        final String normalizedLabel = label;
        List<ReferenceProjection> existing = config.projections();
        for (ReferenceProjection projection : existing) {
            if (!output.equals(projection.outputField())) continue;
            if (!normalizedLabel.equals(projection.targetField())) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.metadata-field-reference-config.reference-label-projection-conflicts-with-standard-output-field",
                        "Reference label projection conflicts with standard output field: {value1}"), Map.of("value1", String.valueOf(output)));
            }
        }
        java.util.ArrayList<String> mappings = new java.util.ArrayList<>();
        mappings.add(normalizedLabel + ":" + output);
        existing.stream()
                .filter(item -> !(normalizedLabel.equals(item.targetField()) && output.equals(item.outputField())))
                .forEach(item -> mappings.add(item.targetField() + ":" + item.outputField()));
        config.setProjectionMappings(MetadataFieldReferenceConfig.encodeProjections(mappings));
    }

    private String defaultTargetLabelField(MetadataFieldReferenceConfig config, ReferenceTarget target) {
        if (targetsStaticEntity(config)) {
            ReferenceAbility<?> ability = PlatformAbilityRuntime.referenceTargetResolver().resolve(target)
                    .orElseThrow(() -> PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.metadata-field-reference-config.static-reference-target-is-not-registered",
                            "Static reference target is not registered: {value1}"), Map.of("value1", String.valueOf(target.qualifiedName()))));
            return ability.referenceCandidateLabels().stream()
                    .filter(net.ximatai.muyun.spring.ability.reference.ReferenceCandidateField::defaultField)
                    .map(net.ximatai.muyun.spring.ability.reference.ReferenceCandidateField::fieldName)
                    .findFirst().orElse("title");
        }
        if (config.getTargetMetadataId() != null) {
            return fieldService.list(Criteria.of().eq("metadataId", config.getTargetMetadataId()), PageRequests.all())
                    .stream().filter(field -> Boolean.TRUE.equals(field.getTitleField()))
                    .map(MetadataField::getFieldName).findFirst().orElse("title");
        }
        return "title";
    }

    private void normalizeTargetIdentifiers(MetadataFieldReferenceConfig config) {
        config.setTargetMetadataId(normalizeBlank(config.getTargetMetadataId()));
        String targetModuleAlias = normalizeBlank(config.getTargetModuleAlias());
        if (targetModuleAlias == null) {
            config.setTargetModuleAlias(null);
            return;
        }
        targetModuleAlias = PlatformNameRules.requireModuleAlias(targetModuleAlias);
        if (moduleService.select(targetModuleAlias) == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field-reference-config.reference-config-requires-existing-target-module",
                    "Reference config requires existing target module: {value1}"), Map.of("value1", String.valueOf(targetModuleAlias)));
        }
        config.setTargetModuleAlias(targetModuleAlias);
    }

    private ReferenceTarget resolveStaticTarget(MetadataFieldReferenceConfig config,
                                                 ModuleMetadataRelation sourceRelation) {
        if (config.getTargetMetadataId() != null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field-reference-config.static-reference-target-cannot-declare-target-metadata",
                    "Static reference target cannot declare target metadata: {value1}"), Map.of("value1", String.valueOf(config.getTargetMetadataId())));
        }
        if (sourceRelation == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field-reference-config.static-reference-config-must-be-relation-scoped",
                    "Static reference config must be relation-scoped: {value1}"), Map.of("value1", String.valueOf(config.getMetadataFieldId())));
        }
        String targetModuleAlias = config.getTargetModuleAlias();
        if (targetModuleAlias == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field-reference-config.static-reference-config-requires-target-module",
                    "Static reference config requires target module: {value1}"), Map.of("value1", String.valueOf(config.getMetadataFieldId())));
        }
        PlatformModule targetPlatformModule = moduleService.select(targetModuleAlias);
        if (targetPlatformModule == null || targetPlatformModule.getModuleKind() != ModuleKind.STATIC) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field-reference-config.static-reference-config-requires-static-target-module",
                    "Static reference config requires STATIC target module: {value1}"), Map.of("value1", String.valueOf(targetModuleAlias)));
        }
        ReferenceTarget target = ReferenceTargets.fromModuleAlias(targetModuleAlias);
        ReferenceAbility<?> ability = PlatformAbilityRuntime.referenceTargetResolver().resolve(target)
                .orElseThrow(() -> PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.metadata-field-reference-config.static-reference-target-is-not-registered",
                        "Static reference target is not registered: {value1}"), Map.of("value1", String.valueOf(target.qualifiedName()))));
        if (!target.equals(ReferenceTargets.of(ability))) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field-reference-config.static-reference-target-identity-mismatch",
                    "Static reference target identity mismatch: {value1}"), Map.of("value1", String.valueOf(target.qualifiedName())));
        }
        return target;
    }

    private ReferenceTarget resolveDynamicTarget(MetadataFieldReferenceConfig config,
                                                  MetadataField sourceField,
                                                  ModuleMetadataRelation sourceRelation) {
        if (config.getTargetMetadataId() == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field-reference-config.reference-config-requires-target-metadata-or-static-target-entity",
                    "Reference config requires target metadata or static target entity: {value1}"), Map.of("value1", String.valueOf(config.getMetadataFieldId())));
        }
        Metadata targetMetadata = metadataService.select(config.getTargetMetadataId());
        if (targetMetadata == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field-reference-config.reference-config-requires-existing-target-metadata",
                    "Reference config requires existing target metadata: {value1}"), Map.of("value1", String.valueOf(config.getTargetMetadataId())));
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

    private String normalizeFieldName(String value, String name) {
        String normalized = normalizeBlank(value);
        return normalized == null ? null : PlatformNameRules.requireFieldName(normalized, name);
    }

    private void validateCodeFieldExclusion(MetadataField source, ModuleMetadataRelation scopedRelation) {
        if (source.getId() == null) return;
        // Use the same entity locks as code activation so concurrent declarations cannot cross.
        var affected = relationService.list(Criteria.of().eq("metadataId", source.getMetadataId()), PageRequests.all()).stream()
                .filter(relation -> scopedRelation == null || Objects.equals(relation.getId(), scopedRelation.getId()))
                .sorted(java.util.Comparator.comparing(ModuleMetadataRelation::getModuleAlias).thenComparing(ModuleMetadataRelation::getId)).toList();
        for (var relation : affected) {
            var schema = metadataService.select(relation.getMetadataId());
            net.ximatai.muyun.spring.platform.code.CodeConfigurationLocks.lock(relation.getModuleAlias(), schema.getAlias());
            var binding = moduleFields.getObject().findByRelationAndField(relation.getId(), source.getId());
            if (binding != null && binding.getBusinessCodeRole() != null)
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.metadata-field-reference-config.code-field-cannot-be-reference", "Business code fields cannot become references"));
        }
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
                    if (targetsStaticEntity(config)) {
                        throw PlatformErrors.badRequestText(TextReference.code(
                                "platform.rejection.metadata-field-reference-config.child-relation-foreign-key-cannot-reference-a-static-target",
                                "Child relation foreign key cannot reference a static target: {value1}"), Map.of("value1", String.valueOf(relation.getRelationAlias())));
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
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field-reference-config.reference-config-requires-existing-relation",
                    "Reference config requires existing relation: {value1}"), Map.of("value1", String.valueOf(config.getRelationId())));
        }
        if (!field.getMetadataId().equals(relation.getMetadataId())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field-reference-config.reference-config-relation-metadata-mismatch",
                    "Reference config relation metadata mismatch: {value1}"), Map.of("value1", String.valueOf(config.getRelationId())));
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
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field-reference-config.cross-module-reference-config-must-be-relation-scoped",
                    "Cross-module reference config must be relation-scoped: {value1}"), Map.of("value1", String.valueOf(config.getMetadataFieldId())));
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
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field-reference-config.cross-module-reference-target-must-be-the-target-module-main-metadata",
                    "Cross-module reference target must be the target module MAIN metadata: {value1}"), Map.of("value1", String.valueOf(targetModuleAlias)));
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
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.metadata-field-reference-config.reference-target-metadata-is-not-bound-to-module",
                        "Reference target metadata is not bound to module: {value1}.{value2}"), Map.of("value1", String.valueOf(targetModuleAlias), "value2", String.valueOf(config.getTargetMetadataId())));
            }
        }
    }

    private void validateOutputFields(MetadataFieldReferenceConfig config,
                                      String sourceMetadataId,
                                      ReferenceTarget target) {
        requireTargetFieldIfPresent(config, target, config.getTargetKeyField());
        requireTargetFieldIfPresent(config, target, config.getTargetLabelField());
        LinkedHashSet<String> outputFields = new LinkedHashSet<>();
        for (ReferenceProjection projection : config.projections()) {
            PlatformNameRules.requireFieldName(projection.targetField(), "projection.targetField");
            PlatformNameRules.requireFieldName(projection.outputField(), "projection.outputField");
            requireTargetField(config, target, projection.targetField());
            requireAvailableOutputField(sourceMetadataId, projection.outputField());
            if (!outputFields.add(projection.outputField())) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.metadata-field-reference-config.duplicate-reference-output-field",
                        "Duplicate reference output field: {value1}"), Map.of("value1", String.valueOf(projection.outputField())));
            }
        }
    }

    private void validateAffects(MetadataFieldReferenceConfig config, MetadataField source, ReferenceTarget target) {
        var mappings = config.affects();
        if (mappings.size() > 8 || (config.getAffectMappings() != null && config.getAffectMappings().length() > 512))
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field-reference-config.selection-backfill-allows-up-to-eight-mappings-and-512-characters-in-total",
                    "最多配置八项选择回填，配置总长不得超过512字符"));
        Set<String> destinations = new LinkedHashSet<>();
        for (var mapping : mappings) {
            requireTargetField(config, target, mapping.referenceField());
            var destination = fieldService.list(Criteria.of().eq("metadataId", source.getMetadataId())
                    .eq("fieldName", mapping.targetField()), PageRequests.all()).stream().findFirst().orElse(null);
            if (destination == null || destination.getFieldOwnership() != MetadataFieldOwnership.BUSINESS
                    || destination.getFieldForm() != MetadataFieldForm.PHYSICAL || Boolean.TRUE.equals(destination.getSystemManaged())
                    || STANDARD_FIELDS.contains(mapping.targetField()) || source.getFieldName().equals(mapping.targetField()))
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.metadata-field-reference-config.selection-backfill-targets-must-be-saved-business-fields-of-the-current-entity",
                        "选择回填目标必须是当前实体已保存的普通业务字段：{value1}"), Map.of("value1", String.valueOf(mapping.targetField())));
            FieldType sourceType = affectSourceType(config, target, mapping.referenceField());
            FieldType destinationType = fieldTypeService.requireFieldType(destination.getFieldSpecAlias()).getFieldType();
            requireAffectAssignment(sourceType, destinationType, mapping.referenceField(), mapping.targetField());
            if (!destinations.add(mapping.targetField()))
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.metadata-field-reference-config.selection-backfill-targets-must-be-unique",
                        "选择回填目标不能重复：{value1}"), Map.of("value1", String.valueOf(mapping.targetField())));
        }
        config.setAffectMappings(MetadataFieldReferenceConfig.encodeProjections(MetadataFieldReferenceConfig.affectMappings(config)));
    }

    private static void requireAffectAssignment(FieldType source, FieldType destination, String from, String to) {
        if (!canAssignAffect(source, destination)) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field-reference-config.selection-backfill-field-types-are-incompatible",
                    "选择回填字段类型不兼容：{value1} → {value2}"), Map.of("value1", String.valueOf(from), "value2", String.valueOf(to)));
        }
    }

    private FieldType affectSourceType(MetadataFieldReferenceConfig config, ReferenceTarget target, String name) {
        if (!targetsStaticEntity(config)) {
            MetadataField field = fieldService.list(Criteria.of().eq("metadataId", config.getTargetMetadataId())
                    .eq("fieldName", name), PageRequests.all()).getFirst();
            return fieldTypeService.requireFieldType(field.getFieldSpecAlias()).getFieldType();
        }
        ReferenceAbility<?> ability = PlatformAbilityRuntime.referenceTargetResolver().resolve(target).orElseThrow();
        var field = org.springframework.util.ReflectionUtils.findField(ability.modelClass(), name);
        Class<?> type = field == null ? null : field.getType();
        if (type == String.class) return FieldType.STRING;
        if (type == Integer.class || type == int.class || type == Short.class || type == short.class
                || type == Byte.class || type == byte.class) return FieldType.INTEGER;
        if (type == Long.class || type == long.class) return FieldType.LONG;
        if (type == java.math.BigDecimal.class) return FieldType.DECIMAL;
        if (type == Boolean.class || type == boolean.class) return FieldType.BOOLEAN;
        if (type == java.time.LocalDate.class) return FieldType.DATE;
        if (type == java.time.Instant.class) return FieldType.TIMESTAMP;
        return null;
    }

    // Selection copies scalar facts; it is not a conversion engine. Numeric widening follows
    // the standard form's numeric parsing, while JSON and zoned instants need richer contracts.
    private static boolean canAssignAffect(FieldType source, FieldType destination) {
        if (source == null || destination == null || source == FieldType.JSON || destination == FieldType.JSON
                || source == FieldType.ZONED_TIMESTAMP || destination == FieldType.ZONED_TIMESTAMP) return false;
        if (source == destination) return true;
        if ((source == FieldType.STRING || source == FieldType.TEXT)
                && (destination == FieldType.STRING || destination == FieldType.TEXT)) return true;
        return source == FieldType.INTEGER && (destination == FieldType.LONG || destination == FieldType.DECIMAL)
                || source == FieldType.LONG && destination == FieldType.DECIMAL;
    }

    private void requireTargetFieldIfPresent(MetadataFieldReferenceConfig config,
                                             ReferenceTarget target,
                                             String fieldName) {
        if (fieldName == null || fieldName.isBlank() || "id".equals(fieldName)) return;
        requireTargetField(config, target, fieldName);
    }

    private void validateTargetKey(MetadataFieldReferenceConfig config, ReferenceTarget target) {
        String keyField = config.getTargetKeyField();
        if (keyField == null || keyField.isBlank() || "id".equals(keyField)) return;
        if (targetsStaticEntity(config)) {
            ReferenceAbility<?> ability = PlatformAbilityRuntime.referenceTargetResolver().resolve(target)
                    .orElseThrow(() -> PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.metadata-field-reference-config.static-reference-target-is-not-registered",
                            "Static reference target is not registered: {value1}"), Map.of("value1", String.valueOf(target.qualifiedName()))));
            net.ximatai.muyun.spring.ability.reference.ReferenceCandidateKey candidate =
                    ability.referenceCandidateKey(keyField);
            if (!candidate.readable() || !candidate.unique()) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.metadata-field-reference-config.static-reference-target-key-must-be-a-readable-unique-field",
                        "Static reference target key must be a readable unique field: {value1}.{value2}"), Map.of("value1", String.valueOf(target.qualifiedName()), "value2", String.valueOf(keyField)));
            }
            return;
        }
        MetadataField targetField = fieldService.list(Criteria.of()
                        .eq("metadataId", config.getTargetMetadataId())
                        .eq("fieldName", keyField), PageRequests.all())
                .stream().findFirst().orElse(null);
        if (targetField == null || !Boolean.TRUE.equals(targetField.getUniqueField())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field-reference-config.dynamic-reference-target-key-must-use-a-unique-target-field",
                    "Dynamic reference target key must use a unique target field: {value1}.{value2}"), Map.of("value1", String.valueOf(target.qualifiedName()), "value2", String.valueOf(keyField)));
        }
    }

    private void requireTargetField(MetadataFieldReferenceConfig config,
                                    ReferenceTarget target,
                                    String targetFieldName) {
        if (targetsStaticEntity(config)) {
            ReferenceAbility<?> ability = PlatformAbilityRuntime.referenceTargetResolver().resolve(target)
                    .orElseThrow(() -> PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.metadata-field-reference-config.static-reference-target-is-not-registered",
                            "Static reference target is not registered: {value1}"), Map.of("value1", String.valueOf(target.qualifiedName()))));
            StaticReferenceResolver.requireReadableField(ability.modelClass(), targetFieldName,
                    "reference projection target");
            return;
        }
        if (fieldService.count(Criteria.of()
                .eq("metadataId", config.getTargetMetadataId())
                .eq("fieldName", targetFieldName)) <= 0) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field-reference-config.reference-projection-requires-existing-target-field",
                    "Reference projection requires existing target field: {value1}"), Map.of("value1", String.valueOf(targetFieldName)));
        }
    }

    private void requireAvailableOutputField(String sourceMetadataId, String outputField) {
        if (STANDARD_FIELDS.contains(outputField)
                || fieldService.count(Criteria.of()
                .eq("metadataId", sourceMetadataId)
                .eq("fieldName", outputField)) > 0) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field-reference-config.projection-output-conflict",
                    "reference projection output field conflicts with source field: {value1}"), Map.of("value1", outputField));
        }
    }

    private boolean targetsStaticEntity(MetadataFieldReferenceConfig config) {
        if (config.getTargetMetadataId() != null || config.getTargetModuleAlias() == null) {
            return false;
        }
        PlatformModule target = moduleService.select(config.getTargetModuleAlias());
        return target != null && target.getModuleKind() == ModuleKind.STATIC;
    }

    private MetadataField requireField(String metadataFieldId, String name) {
        MetadataField field = metadataFieldId == null || metadataFieldId.isBlank() ? null : fieldService.select(metadataFieldId);
        if (field == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-field-reference-config.reference-config-requires-existing",
                    "Reference config requires existing {value1}: {value2}"), Map.of("value1", String.valueOf(name), "value2", String.valueOf(metadataFieldId)));
        }
        return field;
    }

}
