package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.PlatformManagedMutationContext;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.ability.reference.ReferenceSelectionProjection;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMeasureUnitConversionMode;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMeasureUnitMode;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMoneyMode;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class ModuleMetadataFieldService extends AbstractAbilityService<ModuleMetadataField> implements
        SoftDeleteAbility<ModuleMetadataField>,
        SortAbility<ModuleMetadataField>,
        QueryAbility<ModuleMetadataField> {
    public static final String MODULE_ALIAS = "platform.module_metadata_field";
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final ModuleMetadataRelationService relationService;
    private final MetadataService metadataService;
    private final MetadataFieldService fieldService;
    private final FieldSpecService fieldTypeService;
    private final ModuleMetadataFieldReferenceGenerateRuleValidator referenceGenerateRuleValidator;
    private final PlatformDynamicRuntimeRefreshCoordinator runtimeRefreshCoordinator;
    private final ObjectProvider<ConfigurationReferenceDeletionGuard> referenceGuardProvider;

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
            @Override public T getObject() { return value; }
        };
    }

    public ModuleMetadataFieldService(BaseDao<ModuleMetadataField, String> moduleMetadataFieldDao,
                                      ModuleMetadataRelationService relationService,
                                      MetadataService metadataService,
                                      MetadataFieldService fieldService) {
        this(moduleMetadataFieldDao, relationService, metadataService, fieldService, null, Optional.empty());
    }

    public ModuleMetadataFieldService(BaseDao<ModuleMetadataField, String> moduleMetadataFieldDao,
                                      ModuleMetadataRelationService relationService,
                                      MetadataService metadataService,
                                      MetadataFieldService fieldService,
                                      Optional<ModuleMetadataFieldReferenceGenerateRuleValidator> referenceGenerateRuleValidator) {
        this(moduleMetadataFieldDao, relationService, metadataService, fieldService, null, referenceGenerateRuleValidator);
    }

    public ModuleMetadataFieldService(BaseDao<ModuleMetadataField, String> moduleMetadataFieldDao,
                                      ModuleMetadataRelationService relationService,
                                      MetadataService metadataService,
                                      MetadataFieldService fieldService,
                                      FieldSpecService fieldTypeService,
                                      Optional<ModuleMetadataFieldReferenceGenerateRuleValidator> referenceGenerateRuleValidator) {
        this(moduleMetadataFieldDao, relationService, metadataService, fieldService, fieldTypeService,
                referenceGenerateRuleValidator, Optional.empty(), provider(null));
    }

    public ModuleMetadataFieldService(BaseDao<ModuleMetadataField, String> moduleMetadataFieldDao,
                                      ModuleMetadataRelationService relationService,
                                      MetadataService metadataService,
                                      MetadataFieldService fieldService,
                                      FieldSpecService fieldTypeService,
                                      Optional<ModuleMetadataFieldReferenceGenerateRuleValidator> referenceGenerateRuleValidator,
                                      Optional<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinator) {
        this(moduleMetadataFieldDao, relationService, metadataService, fieldService, fieldTypeService,
                referenceGenerateRuleValidator, runtimeRefreshCoordinator, provider(null));
    }

    @Autowired
    public ModuleMetadataFieldService(BaseDao<ModuleMetadataField, String> moduleMetadataFieldDao,
                                      ModuleMetadataRelationService relationService,
                                      MetadataService metadataService,
                                      MetadataFieldService fieldService,
                                      FieldSpecService fieldTypeService,
                                      Optional<ModuleMetadataFieldReferenceGenerateRuleValidator> referenceGenerateRuleValidator,
                                      Optional<PlatformDynamicRuntimeRefreshCoordinator> runtimeRefreshCoordinator,
                                      ObjectProvider<ConfigurationReferenceDeletionGuard> referenceGuardProvider) {
        super(MODULE_ALIAS, ModuleMetadataField.class, moduleMetadataFieldDao);
        this.relationService = relationService;
        this.metadataService = metadataService;
        this.fieldService = fieldService;
        this.fieldTypeService = fieldTypeService;
        this.referenceGenerateRuleValidator = referenceGenerateRuleValidator == null
                ? null
                : referenceGenerateRuleValidator.orElse(null);
        this.runtimeRefreshCoordinator = runtimeRefreshCoordinator.orElse(null);
        this.referenceGuardProvider = referenceGuardProvider;
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, ModuleMetadataField.class, java.util.List.of("id", "relationId", "metadataFieldId", "cloneable", "dictionaryApplicationAlias", "dictionaryCategoryAlias", "referenceModuleAlias", "referenceTargetUnavailablePolicy", "referenceModuleKeyField", "referenceModuleLabelField", "referenceGenerateRuleId", "referenceQueryTemplateId", "unitCategoryAlias", "unitMode", "fixedUnitCode", "defaultUnitCode", "unitFieldId", "baseValueFieldId", "baseUnitCategoryAlias", "baseUnitCode", "unitConversionMode", "conversionScopeFieldId", "unitRequired", "moneyCurrencyMode", "moneyFixedCurrencyCode", "moneyDefaultCurrencyCode", "moneyCurrencyFieldId", "moneyBaseAmountFieldId", "moneyBaseCurrencyCode", "moneyRateTypeCode", "moneyRateDateFieldId", "moneyExchangeRateFieldId", "moneyCurrencyRequired", "title", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"));
    }

    @Override
    public void beforeDelete(String id) {
        ConfigurationReferenceDeletionGuard guard = referenceGuardProvider.getIfAvailable();
        if (guard != null) guard.assertCanDelete(ConfigurationReferenceTarget.MODULE_METADATA_FIELD, id);
    }

    @Override
    public void beforeInsert(ModuleMetadataField moduleField) {
        normalizeAndValidate(moduleField);
    }

    @Override
    public void beforeUpdate(ModuleMetadataField moduleField) {
        normalizeAndValidate(moduleField);
    }

    @Override
    @Transactional
    public String insert(ModuleMetadataField moduleField) {
        return SoftDeleteAbility.super.insert(moduleField);
    }

    @Override
    @Transactional
    public int update(ModuleMetadataField moduleField) {
        return SoftDeleteAbility.super.update(moduleField);
    }

    @Override
    public void afterChanged(ModuleMetadataField moduleField) {
        if (runtimeRefreshCoordinator != null) {
            runtimeRefreshCoordinator.refreshByModuleField(moduleField);
        }
    }

    public List<ModuleMetadataField> ensureForRelation(String relationId) {
        ModuleMetadataRelation relation = requireRelation(relationId);
        List<MetadataField> fields = fieldService.list(
                Criteria.of().eq("metadataId", relation.getMetadataId()),
                ALL,
                Sort.asc(PlatformAbilityFields.SORT_FIELD)
        );
        for (MetadataField field : fields) {
            if (findByRelationAndField(relation.getId(), field.getId()) == null) {
                insert(createModuleField(relation, field));
            }
        }
        return listByRelationId(relation.getId());
    }

    public List<ModuleMetadataField> listByRelationId(String relationId) {
        if (relationId == null || relationId.isBlank()) {
            return List.of();
        }
        return list(Criteria.of().eq("relationId", relationId), ALL, Sort.asc(PlatformAbilityFields.SORT_FIELD));
    }

    public List<ModuleMetadataField> listByModuleAlias(String moduleAlias) {
        String validAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        List<String> relationIds = relationService.list(Criteria.of().eq("moduleAlias", validAlias),
                        ALL, Sort.asc(PlatformAbilityFields.SORT_FIELD))
                .stream()
                .map(ModuleMetadataRelation::getId)
                .toList();
        if (relationIds.isEmpty()) {
            return List.of();
        }
        return list(Criteria.of().in("relationId", relationIds), ALL, Sort.asc(PlatformAbilityFields.SORT_FIELD));
    }

    public List<ModuleMetadataField> listMainByModuleAlias(String moduleAlias) {
        String validAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        List<String> relationIds = relationService.list(Criteria.of()
                                .eq("moduleAlias", validAlias)
                                .eq("relationRole", RelationRole.MAIN),
                        ALL, Sort.asc(PlatformAbilityFields.SORT_FIELD))
                .stream()
                .map(ModuleMetadataRelation::getId)
                .toList();
        if (relationIds.isEmpty()) {
            return List.of();
        }
        return list(Criteria.of().in("relationId", relationIds), ALL, Sort.asc(PlatformAbilityFields.SORT_FIELD));
    }

    public ResolvedModuleMetadataField resolve(String moduleMetadataFieldId) {
        ModuleMetadataField moduleField = moduleMetadataFieldId == null || moduleMetadataFieldId.isBlank()
                ? null
                : select(moduleMetadataFieldId);
        if (moduleField == null) {
            throw new PlatformException("Module metadata field requires existing config: " + moduleMetadataFieldId);
        }
        ModuleMetadataRelation relation = requireRelation(moduleField.getRelationId());
        Metadata metadata = requireMetadata(relation.getMetadataId());
        MetadataField field = requireField(moduleField.getMetadataFieldId());
        if (!metadata.getId().equals(field.getMetadataId())) {
            throw new PlatformException("Module metadata field metadata mismatch: " + moduleField.getId());
        }
        return new ResolvedModuleMetadataField(
                moduleField.getId(),
                relation.getModuleAlias(),
                relation.getId(),
                relation.getRelationAlias(),
                relation.getRelationRole(),
                metadata.getId(),
                metadata.getAlias(),
                metadata.getTitle(),
                field.getId(),
                field.getFieldName(),
                field.getColumnName(),
                field.getTitle(),
                field.getFieldSpecAlias(),
                field.getFieldForm()
        );
    }

    private void normalizeAndValidate(ModuleMetadataField moduleField) {
        ModuleMetadataRelation relation = requireRelation(moduleField.getRelationId());
        Metadata metadata = requireMetadata(relation.getMetadataId());
        MetadataField field = requireField(moduleField.getMetadataFieldId());
        if (!relation.getMetadataId().equals(field.getMetadataId())) {
            throw new PlatformException("Module metadata field requires field in relation metadata: "
                    + moduleField.getMetadataFieldId());
        }
        validateVirtualFieldBoundary(moduleField, field);
        normalizeReferenceConfig(moduleField, metadata, relation);
        validateChildForeignKeyReference(moduleField, relation, field);
        normalizeMeasureUnitConfig(moduleField, metadata, relation, field);
        normalizeMoneyConfig(moduleField, metadata, relation, field);
        rejectDuplicate(moduleField, Criteria.of()
                        .eq("relationId", relation.getId())
                        .eq("metadataFieldId", field.getId()),
                "module metadata field must be unique: " + relation.getId() + "." + field.getId());
        moduleField.setRelationId(relation.getId());
        moduleField.setMetadataFieldId(field.getId());
    }

    private void validateChildForeignKeyReference(ModuleMetadataField moduleField,
                                                   ModuleMetadataRelation relation,
                                                   MetadataField field) {
        if (relation.getRelationRole() == RelationRole.CHILD
                && field.getFieldName().equals(relation.getForeignKey())
                && hasText(moduleField.getReferenceModuleAlias())) {
            throw new PlatformException("Child relation foreign key cannot use module reference configuration: "
                    + field.getFieldName());
        }
    }

    private void validateVirtualFieldBoundary(ModuleMetadataField moduleField, MetadataField field) {
        if (field.getFieldForm() != MetadataFieldForm.VIRTUAL) {
            return;
        }
        if (hasText(moduleField.getDefaultValue()) || hasText(moduleField.getValidationRegex())) {
            throw new PlatformException("Virtual module metadata field cannot define default value or validation regex: "
                    + field.getFieldName());
        }
        if (hasMeasureConfig(moduleField)) {
            throw new PlatformException("Virtual module metadata field cannot define measure unit config: "
                    + field.getFieldName());
        }
        if (hasMoneyConfig(moduleField)) {
            throw new PlatformException("Virtual module metadata field cannot define money config: "
                    + field.getFieldName());
        }
    }

    private boolean hasMeasureConfig(ModuleMetadataField moduleField) {
        return hasText(moduleField.getUnitCategoryAlias())
                || moduleField.getUnitMode() != null
                || hasText(moduleField.getFixedUnitCode())
                || hasText(moduleField.getDefaultUnitCode())
                || hasText(moduleField.getUnitFieldId())
                || hasText(moduleField.getBaseValueFieldId())
                || hasText(moduleField.getBaseUnitCategoryAlias())
                || hasText(moduleField.getBaseUnitCode())
                || moduleField.getUnitConversionMode() != null
                || hasText(moduleField.getConversionScopeFieldId());
    }

    private boolean hasMoneyConfig(ModuleMetadataField moduleField) {
        return moduleField.getMoneyCurrencyMode() != null
                || hasText(moduleField.getMoneyFixedCurrencyCode())
                || hasText(moduleField.getMoneyDefaultCurrencyCode())
                || hasText(moduleField.getMoneyCurrencyFieldId())
                || hasText(moduleField.getMoneyBaseAmountFieldId())
                || hasText(moduleField.getMoneyBaseCurrencyCode())
                || hasText(moduleField.getMoneyRateTypeCode())
                || hasText(moduleField.getMoneyRateDateFieldId())
                || hasText(moduleField.getMoneyExchangeRateFieldId());
    }

    private void normalizeReferenceConfig(ModuleMetadataField moduleField,
                                          Metadata metadata,
                                          ModuleMetadataRelation relation) {
        if (moduleField.getCloneable() == null) {
            moduleField.setCloneable(Boolean.FALSE);
        }
        if (moduleField.getReferenceTargetUnavailablePolicy() == null) {
            moduleField.setReferenceTargetUnavailablePolicy(ReferenceTargetUnavailablePolicy.PRESERVE_HISTORY);
        }
        boolean hasDictionaryApplication = hasText(moduleField.getDictionaryApplicationAlias());
        boolean hasDictionaryCategory = hasText(moduleField.getDictionaryCategoryAlias());
        if (!hasDictionaryApplication && !hasDictionaryCategory) {
            moduleField.setDictionaryApplicationAlias(null);
            moduleField.setDictionaryCategoryAlias(null);
        } else {
            if (!hasDictionaryCategory) {
                throw new PlatformException("dictionaryCategoryAlias must not be blank");
            }
            String applicationAlias = hasDictionaryApplication
                    ? PlatformNameRules.requireApplicationAlias(moduleField.getDictionaryApplicationAlias())
                    : metadata.getApplicationAlias();
            moduleField.setDictionaryApplicationAlias(applicationAlias);
            moduleField.setDictionaryCategoryAlias(PlatformNameRules.requireIdentifier(
                    moduleField.getDictionaryCategoryAlias(), "dictionaryCategoryAlias"));
        }
        boolean hasReferenceModule = hasText(moduleField.getReferenceModuleAlias());
        if (hasReferenceModule) {
            moduleField.setReferenceModuleAlias(PlatformNameRules.requireModuleAlias(moduleField.getReferenceModuleAlias()));
            moduleField.setReferenceModuleKeyField(PlatformNameRules.requireFieldName(
                    moduleField.getReferenceModuleKeyField(), "referenceModuleKeyField"));
            moduleField.setReferenceModuleLabelField(PlatformNameRules.requireFieldName(
                    moduleField.getReferenceModuleLabelField(), "referenceModuleLabelField"));
            if (hasText(moduleField.getReferenceGenerateRuleId()) && referenceGenerateRuleValidator == null) {
                throw new PlatformException("referenceGenerateRuleId requires generate rule validator");
            }
            if (hasText(moduleField.getReferenceGenerateRuleId())) {
                referenceGenerateRuleValidator.validateReferenceGenerateRule(
                        moduleField.getReferenceGenerateRuleId(),
                        moduleField.getReferenceModuleAlias(),
                        relation.getModuleAlias());
            }
        } else if (hasReferenceDependentConfig(moduleField)) {
            throw new PlatformException("reference module config requires referenceModuleAlias");
        }
        moduleField.setReferenceModulePlusFields(normalizeReferenceSelectionProjectionSet(
                moduleField.getReferenceModulePlusFields(), "referenceModulePlusFields"));
    }

    private void normalizeMeasureUnitConfig(ModuleMetadataField moduleField,
                                            Metadata metadata,
                                            ModuleMetadataRelation relation,
                                            MetadataField field) {
        if (!hasText(moduleField.getUnitCategoryAlias())) {
            clearMeasureUnitConfig(moduleField);
            return;
        }
        if (field.getFieldRole() == MetadataFieldRole.MEASURE_UNIT
                || field.getFieldRole() == MetadataFieldRole.MEASURE_BASE_VALUE) {
            throw new PlatformException("measure unit config must be declared on owner value field: "
                    + field.getFieldName());
        }
        requireNumericField(field, "measure unit value field");
        moduleField.setUnitCategoryAlias(PlatformNameRules.requireIdentifier(
                moduleField.getUnitCategoryAlias(), "unitCategoryAlias"));
        if (hasText(moduleField.getBaseUnitCategoryAlias())) {
            moduleField.setBaseUnitCategoryAlias(PlatformNameRules.requireIdentifier(
                    moduleField.getBaseUnitCategoryAlias(), "baseUnitCategoryAlias"));
        } else {
            moduleField.setBaseUnitCategoryAlias(moduleField.getUnitCategoryAlias());
        }
        moduleField.setBaseUnitCode(PlatformNameRules.requireIdentifier(moduleField.getBaseUnitCode(), "baseUnitCode"));
        if (moduleField.getUnitConversionMode() == null) {
            moduleField.setUnitConversionMode(FieldMeasureUnitConversionMode.LINEAR);
        }
        if (moduleField.getUnitMode() == null) {
            moduleField.setUnitMode(hasText(moduleField.getFixedUnitCode())
                    ? FieldMeasureUnitMode.FIXED
                    : FieldMeasureUnitMode.SELECTABLE);
        }
        if (moduleField.getUnitRequired() == null) {
            moduleField.setUnitRequired(Boolean.FALSE);
        }
        if (hasText(moduleField.getDefaultUnitCode())) {
            moduleField.setDefaultUnitCode(PlatformNameRules.requireIdentifier(
                    moduleField.getDefaultUnitCode(), "defaultUnitCode"));
        }
        MetadataField conversionScopeField = null;
        if (hasText(moduleField.getConversionScopeFieldId())) {
            conversionScopeField = requireField(moduleField.getConversionScopeFieldId());
            if (!Objects.equals(conversionScopeField.getMetadataId(), metadata.getId())) {
                throw new PlatformException("conversionScopeFieldId must belong to same metadata: "
                        + moduleField.getConversionScopeFieldId());
            }
        }
        if (moduleField.getUnitMode() == FieldMeasureUnitMode.FIXED) {
            moduleField.setFixedUnitCode(PlatformNameRules.requireIdentifier(moduleField.getFixedUnitCode(), "fixedUnitCode"));
            moduleField.setUnitFieldId(null);
            if (!hasText(moduleField.getDefaultUnitCode())) {
                moduleField.setDefaultUnitCode(moduleField.getFixedUnitCode());
            }
        } else {
            moduleField.setFixedUnitCode(null);
            MetadataField unitField = hasText(moduleField.getUnitFieldId())
                    ? requireRelatedField(moduleField.getUnitFieldId(), metadata, field,
                    MetadataFieldForm.COMPANION, MetadataFieldRole.MEASURE_UNIT, "unitFieldId")
                    : ensureRelatedField(metadata, field, MetadataFieldForm.COMPANION,
                    MetadataFieldRole.MEASURE_UNIT, field.getFieldName() + "Unit", "string", "Unit");
            requireFieldType(unitField, FieldType.STRING, "measure unit companion field");
            ensureModuleFieldForRelation(relation, unitField);
            moduleField.setUnitFieldId(unitField.getId());
        }
        MetadataField baseValueField = hasText(moduleField.getBaseValueFieldId())
                ? requireRelatedField(moduleField.getBaseValueFieldId(), metadata, field,
                MetadataFieldForm.SHADOW, MetadataFieldRole.MEASURE_BASE_VALUE, "baseValueFieldId")
                : ensureRelatedField(metadata, field, MetadataFieldForm.SHADOW,
                MetadataFieldRole.MEASURE_BASE_VALUE, field.getFieldName() + "Base",
                field.getFieldSpecAlias(), "Base");
        requireNumericField(baseValueField, "measure base value shadow field");
        ensureModuleFieldForRelation(relation, baseValueField);
        moduleField.setBaseValueFieldId(baseValueField.getId());
        if (conversionScopeField != null) {
            moduleField.setConversionScopeFieldId(conversionScopeField.getId());
        }
    }

    private void clearMeasureUnitConfig(ModuleMetadataField moduleField) {
        moduleField.setUnitCategoryAlias(null);
        moduleField.setUnitMode(null);
        moduleField.setFixedUnitCode(null);
        moduleField.setDefaultUnitCode(null);
        moduleField.setUnitFieldId(null);
        moduleField.setBaseValueFieldId(null);
        moduleField.setBaseUnitCategoryAlias(null);
        moduleField.setBaseUnitCode(null);
        moduleField.setUnitConversionMode(null);
        moduleField.setConversionScopeFieldId(null);
        moduleField.setUnitRequired(Boolean.FALSE);
    }

    private void normalizeMoneyConfig(ModuleMetadataField moduleField,
                                      Metadata metadata,
                                      ModuleMetadataRelation relation,
                                      MetadataField field) {
        if (moduleField.getMoneyCurrencyMode() == null
                && !hasText(moduleField.getMoneyBaseAmountFieldId())
                && !hasText(moduleField.getMoneyRateTypeCode())) {
            clearMoneyConfig(moduleField);
            return;
        }
        if (isMoneyRelatedRole(field.getFieldRole())) {
            throw new PlatformException("money config must be declared on owner amount field: "
                    + field.getFieldName());
        }
        requireNumericField(field, "money amount field");
        if (moduleField.getMoneyCurrencyMode() == null) {
            moduleField.setMoneyCurrencyMode(hasText(moduleField.getMoneyFixedCurrencyCode())
                    ? FieldMoneyMode.FIXED
                    : FieldMoneyMode.SELECTABLE);
        }
        if (hasText(moduleField.getMoneyDefaultCurrencyCode())) {
            moduleField.setMoneyDefaultCurrencyCode(requireCurrencyCode(
                    moduleField.getMoneyDefaultCurrencyCode(), "moneyDefaultCurrencyCode"));
        }
        if (hasText(moduleField.getMoneyBaseCurrencyCode())) {
            moduleField.setMoneyBaseCurrencyCode(requireCurrencyCode(
                    moduleField.getMoneyBaseCurrencyCode(), "moneyBaseCurrencyCode"));
        }
        moduleField.setMoneyRateTypeCode(requireRateTypeCode(moduleField.getMoneyRateTypeCode(), "moneyRateTypeCode"));
        if (moduleField.getMoneyCurrencyRequired() == null) {
            moduleField.setMoneyCurrencyRequired(Boolean.TRUE);
        }
        MetadataField rateDateField = null;
        if (hasText(moduleField.getMoneyRateDateFieldId())) {
            rateDateField = requireField(moduleField.getMoneyRateDateFieldId());
            if (!Objects.equals(rateDateField.getMetadataId(), metadata.getId())) {
                throw new PlatformException("moneyRateDateFieldId must belong to same metadata: "
                        + moduleField.getMoneyRateDateFieldId());
            }
            FieldType type = requireFieldType(rateDateField);
            if (type != FieldType.DATE && type != FieldType.TIMESTAMP && type != FieldType.ZONED_TIMESTAMP) {
                throw new PlatformException("money rate date field requires date or timestamp field: "
                        + rateDateField.getFieldName());
            }
        }
        MetadataField exchangeRateField = null;
        if (hasText(moduleField.getMoneyExchangeRateFieldId())) {
            exchangeRateField = requireRelatedField(moduleField.getMoneyExchangeRateFieldId(), metadata, field,
                    MetadataFieldForm.SHADOW, MetadataFieldRole.MONEY_EXCHANGE_RATE, "moneyExchangeRateFieldId");
            requireNumericField(exchangeRateField, "money exchange rate shadow field");
        }
        if (moduleField.getMoneyCurrencyMode() == FieldMoneyMode.FIXED) {
            moduleField.setMoneyFixedCurrencyCode(requireCurrencyCode(
                    moduleField.getMoneyFixedCurrencyCode(), "moneyFixedCurrencyCode"));
            moduleField.setMoneyCurrencyFieldId(null);
            if (!hasText(moduleField.getMoneyDefaultCurrencyCode())) {
                moduleField.setMoneyDefaultCurrencyCode(moduleField.getMoneyFixedCurrencyCode());
            }
        } else {
            moduleField.setMoneyFixedCurrencyCode(null);
            MetadataField currencyField = hasText(moduleField.getMoneyCurrencyFieldId())
                    ? requireRelatedField(moduleField.getMoneyCurrencyFieldId(), metadata, field,
                    MetadataFieldForm.COMPANION, MetadataFieldRole.MONEY_CURRENCY, "moneyCurrencyFieldId")
                    : ensureRelatedField(metadata, field, MetadataFieldForm.COMPANION,
                    MetadataFieldRole.MONEY_CURRENCY, field.getFieldName() + "Currency", "string", "Currency");
            requireTextField(currencyField, "money currency companion field");
            ensureModuleFieldForRelation(relation, currencyField);
            moduleField.setMoneyCurrencyFieldId(currencyField.getId());
        }
        MetadataField baseAmountField = hasText(moduleField.getMoneyBaseAmountFieldId())
                ? requireRelatedField(moduleField.getMoneyBaseAmountFieldId(), metadata, field,
                MetadataFieldForm.SHADOW, MetadataFieldRole.MONEY_BASE_AMOUNT, "moneyBaseAmountFieldId")
                : ensureRelatedField(metadata, field, MetadataFieldForm.SHADOW,
                MetadataFieldRole.MONEY_BASE_AMOUNT, field.getFieldName() + "Base",
                field.getFieldSpecAlias(), "Base Amount");
        requireNumericField(baseAmountField, "money base amount shadow field");
        ensureModuleFieldForRelation(relation, baseAmountField);
        moduleField.setMoneyBaseAmountFieldId(baseAmountField.getId());
        if (rateDateField != null) {
            moduleField.setMoneyRateDateFieldId(rateDateField.getId());
        }
        if (exchangeRateField != null) {
            ensureModuleFieldForRelation(relation, exchangeRateField);
            moduleField.setMoneyExchangeRateFieldId(exchangeRateField.getId());
        }
    }

    private void clearMoneyConfig(ModuleMetadataField moduleField) {
        moduleField.setMoneyCurrencyMode(null);
        moduleField.setMoneyFixedCurrencyCode(null);
        moduleField.setMoneyDefaultCurrencyCode(null);
        moduleField.setMoneyCurrencyFieldId(null);
        moduleField.setMoneyBaseAmountFieldId(null);
        moduleField.setMoneyBaseCurrencyCode(null);
        moduleField.setMoneyRateTypeCode(null);
        moduleField.setMoneyRateDateFieldId(null);
        moduleField.setMoneyExchangeRateFieldId(null);
        moduleField.setMoneyCurrencyRequired(Boolean.TRUE);
    }

    private MetadataField requireRelatedField(String fieldId,
                                              Metadata metadata,
                                              MetadataField owner,
                                              MetadataFieldForm form,
                                              MetadataFieldRole role,
                                              String label) {
        if (!hasText(fieldId)) {
            throw new PlatformException(label + " must not be blank");
        }
        MetadataField related = requireField(fieldId);
        if (!Objects.equals(related.getMetadataId(), metadata.getId())) {
            throw new PlatformException(label + " must belong to same metadata: " + fieldId);
        }
        if (related.getFieldForm() != form || related.getFieldRole() != role) {
            throw new PlatformException(label + " requires " + form + " " + role + " field: " + fieldId);
        }
        if (!Objects.equals(related.getOwnerFieldId(), owner.getId())) {
            throw new PlatformException(label + " must be owned by owner value field: " + fieldId);
        }
        return related;
    }

    private MetadataField ensureRelatedField(Metadata metadata,
                                             MetadataField owner,
                                             MetadataFieldForm form,
                                             MetadataFieldRole role,
                                             String fieldName,
                                             String fieldSpecAlias,
                                             String titleSuffix) {
        MetadataField existing = findOneRelatedField(metadata.getId(), owner.getId(), role);
        if (existing != null) {
            if (existing.getFieldForm() != form) {
                throw new PlatformException("related field form mismatch: " + existing.getFieldName());
            }
            return existing;
        }
        String validFieldName = PlatformNameRules.requireFieldName(fieldName, "relatedFieldName");
        rejectRelatedFieldNameCollision(metadata.getId(), validFieldName, role);
        rejectRelatedColumnNameCollision(metadata.getId(), toColumnName(validFieldName));
        MetadataField field = new MetadataField();
        field.setMetadataId(metadata.getId());
        field.setFieldName(validFieldName);
        field.setColumnName(toColumnName(validFieldName));
        field.setFieldSpecAlias(PlatformNameRules.requireIdentifier(fieldSpecAlias, "relatedFieldTypeAlias"));
        field.setTitle(defaultText(owner.getTitle(), owner.getFieldName()) + " " + titleSuffix);
        field.setFieldForm(form);
        field.setFieldRole(role);
        field.setOwnerFieldId(owner.getId());
        field.setSystemManaged(role == MetadataFieldRole.MEASURE_BASE_VALUE
                || role == MetadataFieldRole.MONEY_BASE_AMOUNT
                || role == MetadataFieldRole.MONEY_EXCHANGE_RATE);
        field.setSortOrder(nextSortOrder(metadata.getId()));
        String id = PlatformManagedMutationContext.runAsPlatformManaged(() -> fieldService.insert(field));
        return fieldService.select(id);
    }

    private ModuleMetadataField ensureModuleFieldForRelation(ModuleMetadataRelation relation,
                                                             MetadataField field) {
        ModuleMetadataField existing = findByRelationAndField(relation.getId(), field.getId());
        if (existing != null) {
            return existing;
        }
        String id = insert(createModuleField(relation, field));
        return select(id);
    }

    private ModuleMetadataField createModuleField(ModuleMetadataRelation relation,
                                                  MetadataField field) {
        ModuleMetadataField moduleField = new ModuleMetadataField();
        moduleField.setRelationId(relation.getId());
        moduleField.setMetadataFieldId(field.getId());
        moduleField.setTitle(field.getTitle());
        moduleField.setSortOrder(field.getSortOrder());
        return moduleField;
    }

    private MetadataField findOneRelatedField(String metadataId, String ownerFieldId, MetadataFieldRole role) {
        List<MetadataField> fields = fieldService.list(Criteria.of()
                        .eq("metadataId", metadataId)
                        .eq("ownerFieldId", ownerFieldId)
                        .eq("fieldRole", role),
                ALL, Sort.asc(PlatformAbilityFields.SORT_FIELD));
        if (fields.size() > 1) {
            throw new PlatformException("related field must be unique for owner and role: "
                    + ownerFieldId + "." + role);
        }
        return fields.stream().findFirst().orElse(null);
    }

    private void rejectRelatedFieldNameCollision(String metadataId, String fieldName, MetadataFieldRole role) {
        MetadataField existing = fieldService.list(Criteria.of()
                        .eq("metadataId", metadataId)
                        .eq("fieldName", fieldName),
                PageRequest.of(1, 1)).stream().findFirst().orElse(null);
        if (existing != null) {
            throw new PlatformException("related field name is already used: " + fieldName);
        }
    }

    private void rejectRelatedColumnNameCollision(String metadataId, String columnName) {
        MetadataField existing = fieldService.list(Criteria.of()
                        .eq("metadataId", metadataId)
                        .eq("columnName", columnName),
                PageRequest.of(1, 1)).stream().findFirst().orElse(null);
        if (existing != null) {
            throw new PlatformException("related column name is already used: " + columnName);
        }
    }

    private Integer nextSortOrder(String metadataId) {
        return fieldService.list(Criteria.of().eq("metadataId", metadataId), ALL, Sort.asc(PlatformAbilityFields.SORT_FIELD))
                .stream()
                .map(MetadataField::getSortOrder)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .map(value -> value + 10)
                .orElse(10);
    }

    private String toColumnName(String fieldName) {
        StringBuilder column = new StringBuilder();
        for (int i = 0; i < fieldName.length(); i++) {
            char ch = fieldName.charAt(i);
            if (Character.isUpperCase(ch)) {
                if (i > 0) {
                    column.append('_');
                }
                column.append(Character.toLowerCase(ch));
            } else {
                column.append(ch);
            }
        }
        return PlatformNameRules.requireDatabaseName(column.toString(), "relatedColumnName");
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void requireNumericField(MetadataField field, String label) {
        FieldType type = requireFieldType(field);
        if (type != FieldType.INTEGER && type != FieldType.LONG && type != FieldType.DECIMAL) {
            throw new PlatformException(label + " requires numeric field: " + field.getFieldName());
        }
    }

    private void requireFieldType(MetadataField field, FieldType expected, String label) {
        FieldType type = requireFieldType(field);
        if (type != expected) {
            throw new PlatformException(label + " requires " + expected + " field: " + field.getFieldName());
        }
    }

    private void requireTextField(MetadataField field, String label) {
        FieldType type = requireFieldType(field);
        if (type != FieldType.STRING && type != FieldType.TEXT) {
            throw new PlatformException(label + " requires text field: " + field.getFieldName());
        }
    }

    private FieldType requireFieldType(MetadataField field) {
        if (fieldTypeService == null) {
            throw new PlatformException("module field config requires FieldSpecService");
        }
        return fieldTypeService.requireFieldType(field.getFieldSpecAlias()).getFieldType();
    }

    private boolean isMoneyRelatedRole(MetadataFieldRole role) {
        return role == MetadataFieldRole.MONEY_CURRENCY
                || role == MetadataFieldRole.MONEY_BASE_AMOUNT
                || role == MetadataFieldRole.MONEY_EXCHANGE_RATE;
    }

    private String requireCurrencyCode(String value, String label) {
        if (!hasText(value)) {
            throw new PlatformException(label + " must not be blank");
        }
        String code = value.trim().toUpperCase();
        if (!code.matches("[A-Z]{3}")) {
            throw new PlatformException(label + " must be ISO 4217 alpha-3 code: " + value);
        }
        return code;
    }

    private String requireRateTypeCode(String value, String label) {
        if (!hasText(value)) {
            throw new PlatformException(label + " must not be blank");
        }
        String code = value.trim().toUpperCase();
        if (!code.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new PlatformException(label + " must use upper snake code: " + value);
        }
        return code;
    }

    private boolean hasReferenceDependentConfig(ModuleMetadataField moduleField) {
        return hasText(moduleField.getReferenceModuleKeyField())
                || hasText(moduleField.getReferenceModuleLabelField())
                || hasText(moduleField.getReferenceGenerateRuleId())
                || hasText(moduleField.getReferenceQueryTemplateId())
                || (moduleField.getReferenceModulePlusFields() != null
                && !moduleField.getReferenceModulePlusFields().isEmpty());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Set<String> normalizeFieldNameSet(Set<String> fields, String label) {
        if (fields == null || fields.isEmpty()) {
            return fields;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String field : fields) {
            normalized.add(PlatformNameRules.requireFieldName(field, label));
        }
        return normalized;
    }

    /**
     * Reference plus fields are client-safe selection projections, not necessarily direct target fields.
     * Store their canonical relative path while runtime compilation validates each declared reference hop.
     */
    private Set<String> normalizeReferenceSelectionProjectionSet(Set<String> fields, String label) {
        if (fields == null || fields.isEmpty()) {
            return fields;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String field : fields) {
            try {
                normalized.add(new ReferenceSelectionProjection(field).key());
            } catch (IllegalArgumentException exception) {
                throw new PlatformException(label + " must be a relative reference field path: " + field, exception);
            }
        }
        return normalized;
    }

    /** Resolves the legacy module-field configuration for one relation-scoped metadata field. */
    public ModuleMetadataField findByRelationAndField(String relationId, String fieldId) {
        return findOne(Criteria.of()
                .eq("relationId", relationId)
                .eq("metadataFieldId", fieldId));
    }

    private ModuleMetadataRelation requireRelation(String relationId) {
        ModuleMetadataRelation relation = relationId == null || relationId.isBlank() ? null : relationService.select(relationId);
        if (relation == null) {
            throw new PlatformException("Module metadata field requires existing relation: " + relationId);
        }
        return relation;
    }

    private Metadata requireMetadata(String metadataId) {
        Metadata metadata = metadataId == null || metadataId.isBlank() ? null : metadataService.select(metadataId);
        if (metadata == null) {
            throw new PlatformException("Module metadata field requires existing metadata: " + metadataId);
        }
        return metadata;
    }

    private MetadataField requireField(String fieldId) {
        MetadataField field = fieldId == null || fieldId.isBlank() ? null : fieldService.select(fieldId);
        if (field == null) {
            throw new PlatformException("Module metadata field requires existing field: " + fieldId);
        }
        return field;
    }
}
