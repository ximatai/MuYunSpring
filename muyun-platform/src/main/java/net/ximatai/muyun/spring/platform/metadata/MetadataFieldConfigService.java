package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;
import net.ximatai.muyun.spring.common.security.FieldProtectionDefinition;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;
import net.ximatai.muyun.spring.dynamic.metadata.FieldBehaviorDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldBehaviorSupport;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategoryService;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

@Service
public class MetadataFieldConfigService extends AbstractAbilityService<MetadataFieldConfig> implements
        SoftDeleteAbility<MetadataFieldConfig> {
    public static final String MODULE_ALIAS = "platform.metadata_field_config";

    private final MetadataFieldService fieldService;
    private final MetadataService metadataService;
    private final FieldSpecService fieldTypeService;
    private final DictionaryCategoryService categoryService;
    private final ModuleMetadataRelationService relationService;
    private final MetadataFieldProtectionConfigService protectionConfigService;
    private final Optional<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinator;

    public MetadataFieldConfigService(BaseDao<MetadataFieldConfig, String> configDao,
                                      MetadataFieldService fieldService,
                                      MetadataService metadataService,
                                      FieldSpecService fieldTypeService,
                                      DictionaryCategoryService categoryService,
                                      ModuleMetadataRelationService relationService) {
        this(configDao, fieldService, metadataService, fieldTypeService, categoryService, relationService, null,
                Optional.empty());
    }

    public MetadataFieldConfigService(BaseDao<MetadataFieldConfig, String> configDao,
                                      MetadataFieldService fieldService,
                                      MetadataService metadataService,
                                      FieldSpecService fieldTypeService,
                                      DictionaryCategoryService categoryService,
                                      ModuleMetadataRelationService relationService,
                                      MetadataFieldProtectionConfigService protectionConfigService) {
        this(configDao, fieldService, metadataService, fieldTypeService, categoryService, relationService,
                protectionConfigService, Optional.empty());
    }

    @Autowired
    public MetadataFieldConfigService(BaseDao<MetadataFieldConfig, String> configDao,
                                      MetadataFieldService fieldService,
                                      MetadataService metadataService,
                                      FieldSpecService fieldTypeService,
                                      DictionaryCategoryService categoryService,
                                      ModuleMetadataRelationService relationService,
                                      MetadataFieldProtectionConfigService protectionConfigService,
                                      Optional<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinator) {
        super(MODULE_ALIAS, MetadataFieldConfig.class, configDao);
        this.fieldService = fieldService;
        this.metadataService = metadataService;
        this.fieldTypeService = fieldTypeService;
        this.categoryService = categoryService;
        this.relationService = relationService;
        this.protectionConfigService = protectionConfigService;
        this.runtimeRefreshCoordinator = runtimeRefreshCoordinator == null ? Optional.empty() : runtimeRefreshCoordinator;
    }

    @Override
    public void beforeInsert(MetadataFieldConfig config) {
        normalizeAndValidate(config);
    }

    @Override
    public void beforeUpdate(MetadataFieldConfig config) {
        normalizeAndValidate(config);
    }

    @Override
    public void afterChanged(MetadataFieldConfig config) {
        if (!MetadataFieldPropertyMutationContext.active()) {
            refreshByMetadataFieldId(config.getMetadataFieldId());
        }
    }

    public MetadataFieldConfig findByMetadataFieldId(String metadataFieldId) {
        if (metadataFieldId == null || metadataFieldId.isBlank()) {
            return null;
        }
        return findOne(Criteria.of()
                .eq("metadataFieldId", metadataFieldId)
                .isNull("relationId"));
    }

    public MetadataFieldConfig findRelationOverride(String metadataFieldId, String relationId) {
        if (metadataFieldId == null || metadataFieldId.isBlank()) {
            return null;
        }
        if (relationId != null && !relationId.isBlank()) {
            return findOne(Criteria.of()
                    .eq("metadataFieldId", metadataFieldId)
                    .eq("relationId", relationId));
        }
        return null;
    }

    private void normalizeAndValidate(MetadataFieldConfig config) {
        MetadataField field = requireField(config.getMetadataFieldId());
        normalizeRelation(config, field);
        validateDictionaryDraft(config, field);
        validateVirtualQueryBoundary(config, field);
        FieldSpec fieldType = fieldTypeService.requireFieldType(field.getFieldSpecAlias());
        normalizeQueryDefinition(config, fieldType);
        validateProtectionQueryBoundary(config, fieldType);
        normalizeBehavior(config, fieldType);
        validateVirtualBehaviorBoundary(config, field);
        rejectDuplicate(config, scopeCriteria(config.getMetadataFieldId(), config.getRelationId()),
                "metadata field config must be unique in scope: " + config.getMetadataFieldId());
    }

    /**
     * Validates the physical shape and dictionary binding of a field proposal without requiring
     * that the field itself has been inserted.  Query/behavior validation remains part of the
     * persisted-config path because it can depend on protection configuration keyed by field id.
     */
    public void validateDictionaryDraft(MetadataFieldConfig config, MetadataField field) {
        if (config == null) throw new IllegalArgumentException("field config must not be null");
        if (field == null) throw new IllegalArgumentException("metadata field must not be null");
        FieldSpec fieldType = fieldTypeService.requireFieldType(field.getFieldSpecAlias());
        normalizeFieldShape(config, fieldType);
        normalizeDictionaryBinding(config, field, fieldType);
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

    private void normalizeRelation(MetadataFieldConfig config, MetadataField field) {
        if (config.getRelationId() == null || config.getRelationId().isBlank()) {
            config.setRelationId(null);
            return;
        }
        ModuleMetadataRelation relation = relationService.select(config.getRelationId());
        if (relation == null) {
            throw new PlatformException("Field config requires existing relation: " + config.getRelationId());
        }
        if (!field.getMetadataId().equals(relation.getMetadataId())) {
            throw new PlatformException("Field config relation metadata mismatch: " + config.getRelationId());
        }
    }

    private void normalizeFieldShape(MetadataFieldConfig config, FieldSpec fieldType) {
        if (config.getRelationId() != null
                && (config.getFieldLength() != null || config.getPrecision() != null || config.getScale() != null)) {
            throw new PlatformException("Relation field config cannot override physical field shape: "
                    + config.getMetadataFieldId());
        }
        FieldShapeRules.validate(fieldType.getFieldType(), config.effectiveLength(fieldType),
                config.effectivePrecision(fieldType), config.effectiveScale(fieldType), config.getMetadataFieldId());
    }

    private void normalizeDictionaryBinding(MetadataFieldConfig config,
                                            MetadataField field,
                                            FieldSpec fieldType) {
        boolean hasCategory = config.getDictionaryCategoryAlias() != null && !config.getDictionaryCategoryAlias().isBlank();
        boolean hasApplication = config.getDictionaryApplicationAlias() != null && !config.getDictionaryApplicationAlias().isBlank();
        if (!hasCategory && !hasApplication) {
            config.setDictionaryApplicationAlias(null);
            config.setDictionaryCategoryAlias(null);
            config.setSelectionMode(null);
            return;
        }
        if (!hasCategory) {
            throw new IllegalArgumentException("dictionaryCategoryAlias must not be blank");
        }
        if (config.getSelectionMode() == null) {
            config.setSelectionMode(OptionSelectionMode.SINGLE);
        }
        if (config.getSelectionMode() == OptionSelectionMode.MULTIPLE) {
            if (fieldType.getFieldType() != FieldType.JSON) {
                throw new IllegalArgumentException("multiple dictionary binding requires JSON field");
            }
        } else if (fieldType.getFieldType() != FieldType.STRING && fieldType.getFieldType() != FieldType.TEXT) {
            throw new IllegalArgumentException("dictionary binding requires string field");
        }
        Metadata metadata = metadataService.select(field.getMetadataId());
        if (metadata == null) {
            throw new PlatformException("Metadata field requires existing metadata: " + field.getMetadataId());
        }
        String applicationAlias = hasApplication
                ? PlatformNameRules.requireApplicationAlias(config.getDictionaryApplicationAlias())
                : metadata.getApplicationAlias();
        config.setDictionaryApplicationAlias(applicationAlias);
        config.setDictionaryCategoryAlias(PlatformNameRules.requireIdentifier(
                config.getDictionaryCategoryAlias(), "dictionaryCategoryAlias"));
        categoryService.requireDictionaryCategory(config.getDictionaryApplicationAlias(), config.getDictionaryCategoryAlias());
    }

    private void normalizeQueryDefinition(MetadataFieldConfig config, FieldSpec fieldType) {
        if (config.getQueryable() == null) {
            config.setDefaultQueryOperator(null);
            config.setQueryOperators(null);
            return;
        }
        if (!config.getQueryable()) {
            config.setDefaultQueryOperator(null);
            config.setQueryOperators(null);
            return;
        }
        if (config.getDefaultQueryOperator() == null) {
            config.setDefaultQueryOperator(DynamicQueryOperator.defaultOperator(fieldType.getFieldType()));
        }
        if (config.getQueryOperators() == null || config.getQueryOperators().isEmpty()) {
            config.setQueryOperators(DynamicQueryOperator.names(DynamicQueryOperator.defaultOperators(fieldType.getFieldType())));
        } else {
            config.setQueryOperators(DynamicQueryOperator.names(DynamicQueryOperator.parseNames(config.getQueryOperators())));
        }
        config.queryDefinition(fieldType);
    }

    private void validateVirtualQueryBoundary(MetadataFieldConfig config, MetadataField field) {
        if (field.getFieldForm() != MetadataFieldForm.VIRTUAL) {
            return;
        }
        if (Boolean.TRUE.equals(config.getQueryable())) {
            throw new PlatformException("Virtual metadata field cannot be queryable: "
                    + config.getMetadataFieldId());
        }
    }

    private void validateProtectionQueryBoundary(MetadataFieldConfig config, FieldSpec fieldType) {
        if (protectionConfigService == null) {
            return;
        }
        FieldProtectionDefinition protection = protectionConfigService.definition(config.getMetadataFieldId());
        if (!protection.hasStorageProtection()) {
            return;
        }
        if (config.queryDefinition(fieldType).queryable()) {
            throw new PlatformException("Protected storage field cannot be queryable: "
                    + config.getMetadataFieldId());
        }
    }

    private void normalizeBehavior(MetadataFieldConfig config, FieldSpec fieldType) {
        if (config.getDefaultValue() != null && config.getDefaultValue().isBlank()) {
            config.setDefaultValue(null);
        }
        if (config.getValidationRegex() != null && config.getValidationRegex().isBlank()) {
            config.setValidationRegex(null);
        }
        FieldBehaviorSupport.validateBehavior(
                fieldType.getFieldType(),
                new FieldBehaviorDefinition(config.getDefaultValue(), config.getValidationRegex(),
                        config.getCopyable() == null || Boolean.TRUE.equals(config.getCopyable()),
                        Boolean.TRUE.equals(config.getWriteProtected())),
                config.getMetadataFieldId()
        );
    }

    private void validateVirtualBehaviorBoundary(MetadataFieldConfig config, MetadataField field) {
        if (field.getFieldForm() != MetadataFieldForm.VIRTUAL) {
            return;
        }
        if (config.getDefaultValue() != null || config.getValidationRegex() != null) {
            throw new PlatformException("Virtual metadata field cannot define default value or validation regex: "
                    + config.getMetadataFieldId());
        }
    }

    private MetadataField requireField(String metadataFieldId) {
        MetadataField field = metadataFieldId == null || metadataFieldId.isBlank() ? null : fieldService.select(metadataFieldId);
        if (field == null) {
            throw new PlatformException("Field config requires existing metadata field: " + metadataFieldId);
        }
        return field;
    }
}
