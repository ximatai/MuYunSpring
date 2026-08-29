package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldBehaviorDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMeasureUnitDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMoneyDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldQueryDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldStorageForm;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MetadataFieldDefinitionCompiler {
    private final FieldSpecService fieldTypeService;
    private final MetadataFieldConfigService configService;
    private final MetadataFieldProtectionConfigService protectionConfigService;
    private final MetadataFieldService fieldService;

    public MetadataFieldDefinitionCompiler(FieldSpecService fieldTypeService,
                                           MetadataFieldConfigService configService) {
        this(fieldTypeService, configService, null, null);
    }

    public MetadataFieldDefinitionCompiler(FieldSpecService fieldTypeService,
                                           MetadataFieldConfigService configService,
                                           MetadataFieldProtectionConfigService protectionConfigService) {
        this(fieldTypeService, configService, protectionConfigService, null);
    }

    @Autowired
    public MetadataFieldDefinitionCompiler(FieldSpecService fieldTypeService,
                                           MetadataFieldConfigService configService,
                                           MetadataFieldProtectionConfigService protectionConfigService,
                                           MetadataFieldService fieldService) {
        this.fieldTypeService = fieldTypeService;
        this.configService = configService;
        this.protectionConfigService = protectionConfigService;
        this.fieldService = fieldService;
    }

    public FieldDefinition compile(MetadataField field) {
        return compile(field, null);
    }

    public FieldDefinition compile(MetadataField field, String relationId) {
        return compile(field, relationId, null);
    }

    public FieldDefinition compile(MetadataField field, String relationId, ModuleMetadataField moduleField) {
        FieldSpec fieldType = fieldTypeService.requireFieldType(field.getFieldSpecAlias());
        MetadataFieldConfig defaultConfig = configService.findByMetadataFieldId(field.getId());
        MetadataFieldConfig relationConfig = configService.findRelationOverride(field.getId(), relationId);
        MetadataFieldConfig shapeConfig = defaultConfig;
        MetadataFieldConfig dictionaryConfig = relationConfig != null && relationConfig.hasDictionaryBinding()
                ? relationConfig
                : defaultConfig;
        boolean hasModuleDictionary = moduleField != null
                && moduleField.getDictionaryCategoryAlias() != null
                && !moduleField.getDictionaryCategoryAlias().isBlank();
        FieldQueryDefinition queryDefinition = field.getFieldForm() == MetadataFieldForm.VIRTUAL
                ? FieldQueryDefinition.disabled()
                : queryDefinition(fieldType, defaultConfig, relationConfig);
        Integer length = shapeConfig == null ? fieldType.getDefaultLength() : shapeConfig.effectiveLength(fieldType);
        Integer precision = shapeConfig == null ? fieldType.getDefaultPrecision() : shapeConfig.effectivePrecision(fieldType);
        Integer scale = shapeConfig == null ? fieldType.getDefaultScale() : shapeConfig.effectiveScale(fieldType);
        FieldDefinition definition = new FieldDefinition(
                field.getFieldName(),
                field.getColumnName(),
                fieldType.getFieldType(),
                field.getTitle(),
                Boolean.TRUE.equals(field.getRequired()),
                Boolean.TRUE.equals(field.getUniqueField()),
                Boolean.TRUE.equals(field.getIndexed()),
                Boolean.TRUE.equals(field.getSortableField()),
                Boolean.TRUE.equals(field.getTitleField()),
                length,
                precision,
                scale,
                null,
                queryDefinition,
                fieldType.getDefaultUiControlAlias(),
                behavior(fieldType, defaultConfig, relationConfig, moduleField, field.getId()),
                protectionConfigService == null
                        ? net.ximatai.muyun.spring.common.security.FieldProtectionDefinition.NONE
                        : protectionConfigService.definition(field.getId()),
                measureUnit(moduleField),
                money(moduleField),
                storageForm(field)
        );
        if (hasModuleDictionary) {
            validateModuleDictionary(fieldType, moduleField, field.getId());
            definition = definition.dictionary(moduleField.getDictionaryApplicationAlias(),
                    moduleField.getDictionaryCategoryAlias());
        } else if (dictionaryConfig != null && dictionaryConfig.hasDictionaryBinding()) {
            definition = definition.dictionary(dictionaryConfig.getDictionaryApplicationAlias(),
                    dictionaryConfig.getDictionaryCategoryAlias(),
                    dictionaryConfig.getSelectionMode());
        }
        if (isJsonSetFieldType(fieldType)) {
            definition = definition.jsonSet();
        }
        FieldDefinition managedDefinition = MetadataCapabilityCatalog.managedDefinition(field);
        return managedDefinition == null ? definition : managedDefinition;
    }

    public FieldQueryDefinition compileQueryDefinition(String metadataFieldId, String relationId) {
        if (fieldService == null) {
            throw new IllegalArgumentException("field query definition compilation requires MetadataFieldService");
        }
        MetadataField field = fieldService.select(metadataFieldId);
        if (field == null) {
            throw new IllegalArgumentException("field query definition points to missing field: " + metadataFieldId);
        }
        if (field.getFieldForm() == MetadataFieldForm.VIRTUAL) {
            return FieldQueryDefinition.disabled();
        }
        FieldSpec fieldType = fieldTypeService.requireFieldType(field.getFieldSpecAlias());
        MetadataFieldConfig defaultConfig = configService.findByMetadataFieldId(field.getId());
        MetadataFieldConfig relationConfig = configService.findRelationOverride(field.getId(), relationId);
        return queryDefinition(fieldType, defaultConfig, relationConfig);
    }

    private FieldQueryDefinition queryDefinition(FieldSpec fieldType,
                                                 MetadataFieldConfig defaultConfig,
                                                 MetadataFieldConfig relationConfig) {
        MetadataFieldConfig queryConfig = relationConfig != null && relationConfig.getQueryable() != null
                ? relationConfig
                : defaultConfig;
        return queryConfig == null
                ? fieldType.queryDefinition()
                : queryConfig.queryDefinition(fieldType);
    }

    private FieldStorageForm storageForm(MetadataField field) {
        return field.getFieldForm() == MetadataFieldForm.VIRTUAL
                ? FieldStorageForm.VIRTUAL
                : FieldStorageForm.PHYSICAL;
    }

    private boolean isJsonSetFieldType(FieldSpec fieldType) {
        return fieldType.getFieldType() == net.ximatai.muyun.spring.dynamic.metadata.FieldType.JSON
                && "json_set".equals(fieldType.getAlias());
    }

    private FieldBehaviorDefinition behavior(FieldSpec fieldType,
                                             MetadataFieldConfig defaultConfig,
                                             MetadataFieldConfig relationConfig,
                                             String fieldId) {
        if (defaultConfig == null && relationConfig == null) {
            return FieldBehaviorDefinition.DEFAULT;
        }
        String defaultValue = relationConfig != null && relationConfig.getDefaultValue() != null
                ? relationConfig.getDefaultValue()
                : defaultConfig == null ? null : defaultConfig.getDefaultValue();
        String validationRegex = relationConfig != null && relationConfig.getValidationRegex() != null
                ? relationConfig.getValidationRegex()
                : defaultConfig == null ? null : defaultConfig.getValidationRegex();
        boolean copyable = relationConfig != null && relationConfig.getCopyable() != null
                ? Boolean.TRUE.equals(relationConfig.getCopyable())
                : defaultConfig == null || defaultConfig.getCopyable() == null || Boolean.TRUE.equals(defaultConfig.getCopyable());
        boolean writeProtected = relationConfig != null && relationConfig.getWriteProtected() != null
                ? Boolean.TRUE.equals(relationConfig.getWriteProtected())
                : defaultConfig != null && Boolean.TRUE.equals(defaultConfig.getWriteProtected());
        FieldBehaviorDefinition behavior = new FieldBehaviorDefinition(
                defaultValue,
                validationRegex,
                copyable,
                writeProtected
        );
        net.ximatai.muyun.spring.dynamic.metadata.FieldBehaviorSupport.validateBehavior(
                fieldType.getFieldType(), behavior, fieldId);
        return behavior;
    }

    private FieldBehaviorDefinition behavior(FieldSpec fieldType,
                                             MetadataFieldConfig defaultConfig,
                                             MetadataFieldConfig relationConfig,
                                             ModuleMetadataField moduleField,
                                             String fieldId) {
        FieldBehaviorDefinition inherited = behavior(fieldType, defaultConfig, relationConfig, fieldId);
        if (moduleField == null) {
            return inherited;
        }
        String defaultValue = moduleField.getDefaultValue() != null
                ? moduleField.getDefaultValue()
                : inherited.defaultValue();
        String validationRegex = moduleField.getValidationRegex() != null
                ? moduleField.getValidationRegex()
                : inherited.validationRegex();
        boolean copyable = moduleField.getCloneable() == null
                ? inherited.copyable()
                : Boolean.TRUE.equals(moduleField.getCloneable());
        FieldBehaviorDefinition behavior = new FieldBehaviorDefinition(
                defaultValue,
                validationRegex,
                copyable,
                inherited.writeProtected()
        );
        net.ximatai.muyun.spring.dynamic.metadata.FieldBehaviorSupport.validateBehavior(
                fieldType.getFieldType(), behavior, fieldId);
        return behavior;
    }

    private void validateModuleDictionary(FieldSpec fieldType,
                                          ModuleMetadataField moduleField,
                                          String fieldId) {
        FieldType type = fieldType.getFieldType();
        if (type != FieldType.STRING && type != FieldType.TEXT) {
            throw new IllegalArgumentException("module field dictionary binding requires string field: " + fieldId);
        }
        if (moduleField.getDictionaryApplicationAlias() == null
                || moduleField.getDictionaryApplicationAlias().isBlank()) {
            throw new IllegalArgumentException("module field dictionaryApplicationAlias must not be blank: " + fieldId);
        }
    }

    private FieldMeasureUnitDefinition measureUnit(ModuleMetadataField moduleField) {
        if (moduleField == null
                || moduleField.getUnitCategoryAlias() == null
                || moduleField.getUnitCategoryAlias().isBlank()) {
            return FieldMeasureUnitDefinition.NONE;
        }
        return new FieldMeasureUnitDefinition(
                moduleField.getUnitCategoryAlias(),
                moduleField.getUnitMode(),
                moduleField.getFixedUnitCode(),
                moduleField.getDefaultUnitCode(),
                fieldName(moduleField.getUnitFieldId()),
                fieldName(moduleField.getBaseValueFieldId()),
                moduleField.getBaseUnitCategoryAlias(),
                moduleField.getBaseUnitCode(),
                moduleField.getUnitConversionMode(),
                fieldName(moduleField.getConversionScopeFieldId()),
                Boolean.TRUE.equals(moduleField.getUnitRequired())
        );
    }

    private String fieldName(String fieldId) {
        if (fieldId == null || fieldId.isBlank()) {
            return null;
        }
        if (fieldService == null) {
            throw new IllegalArgumentException("module field config requires MetadataFieldService");
        }
        MetadataField field = fieldService.select(fieldId);
        if (field == null) {
            throw new IllegalArgumentException("module field config points to missing field: " + fieldId);
        }
        return field.getFieldName();
    }

    private FieldMoneyDefinition money(ModuleMetadataField moduleField) {
        if (moduleField == null
                || moduleField.getMoneyBaseAmountFieldId() == null
                || moduleField.getMoneyBaseAmountFieldId().isBlank()) {
            return FieldMoneyDefinition.NONE;
        }
        return new FieldMoneyDefinition(
                moduleField.getMoneyCurrencyMode(),
                moduleField.getMoneyFixedCurrencyCode(),
                moduleField.getMoneyDefaultCurrencyCode(),
                fieldName(moduleField.getMoneyCurrencyFieldId()),
                fieldName(moduleField.getMoneyBaseAmountFieldId()),
                moduleField.getMoneyBaseCurrencyCode(),
                moduleField.getMoneyRateTypeCode(),
                fieldName(moduleField.getMoneyRateDateFieldId()),
                fieldName(moduleField.getMoneyExchangeRateFieldId()),
                Boolean.TRUE.equals(moduleField.getMoneyCurrencyRequired())
        );
    }
}
