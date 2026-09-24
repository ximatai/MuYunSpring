package net.ximatai.muyun.spring.platform.metadata;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardSortableEntity;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMeasureUnitConversionMode;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMeasureUnitMode;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMoneyMode;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;

import java.util.Set;

@Getter
@Setter
@Table(name = "platform_module_metadata_field", comment = "Module metadata field config")
@CompositeIndex(columns = {"relation_id", "metadata_field_id"}, unique = true)
@net.ximatai.muyun.spring.ability.SortPartitionBy(fields = "relationId")
public class ModuleMetadataField extends StandardSortableEntity {
    @Column(name = "relation_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Module metadata relation id")
    private String relationId;

    @Column(name = "metadata_field_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Metadata field id")
    private String metadataFieldId;

    @Column(name = "default_value", type = ColumnType.VARCHAR, length = 512, comment = "Default value")
    private String defaultValue;

    @Column(name = "cloneable", type = ColumnType.BOOLEAN, comment = "Cloneable flag",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean cloneable = Boolean.FALSE;

    @Column(name = "validation_regex", type = ColumnType.VARCHAR, length = 512, comment = "Validation regex")
    private String validationRegex;

    @Column(name = "dictionary_application_alias", type = ColumnType.VARCHAR, length = 64, comment = "Dictionary application alias")
    private String dictionaryApplicationAlias;

    @Column(name = "dictionary_category_alias", type = ColumnType.VARCHAR, length = 64, comment = "Dictionary category alias")
    private String dictionaryCategoryAlias;

    @Column(name = "reference_module_alias", type = ColumnType.VARCHAR, length = 128, comment = "Reference module alias")
    private String referenceModuleAlias;

    @Column(name = "reference_target_unavailable_policy", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Reference target unavailable policy",
            defaultVal = @Default(varchar = "PRESERVE_HISTORY"))
    private ReferenceTargetUnavailablePolicy referenceTargetUnavailablePolicy = ReferenceTargetUnavailablePolicy.PRESERVE_HISTORY;

    @Column(name = "reference_require_enabled", type = ColumnType.BOOLEAN, nullable = false,
            comment = "Require an enabled reference target on every save",
            defaultVal = @Default(bool = net.ximatai.muyun.database.core.annotation.TrueOrFalse.FALSE))
    private Boolean referenceRequireEnabled = false;

    @Column(name = "reference_module_key_field", type = ColumnType.VARCHAR, length = 64, comment = "Reference module key field")
    private String referenceModuleKeyField;

    @Column(name = "reference_module_label_field", type = ColumnType.VARCHAR, length = 64, comment = "Reference module label field")
    private String referenceModuleLabelField;

    @Column(name = "reference_generate_rule_id", type = ColumnType.VARCHAR, length = 32, comment = "Reference generate rule id")
    private String referenceGenerateRuleId;

    @Column(name = "reference_query_template_id", type = ColumnType.VARCHAR, length = 32, comment = "Reference query template id")
    private String referenceQueryTemplateId;

    @Column(name = "reference_module_plus_fields", type = ColumnType.JSON_SET, comment = "Reference module plus fields")
    private Set<String> referenceModulePlusFields;

    @Column(name = "unit_category_alias", type = ColumnType.VARCHAR, length = 64, comment = "Measure unit category alias")
    private String unitCategoryAlias;

    @Column(name = "unit_mode", type = ColumnType.VARCHAR, length = 32, comment = "Measure unit input mode")
    private FieldMeasureUnitMode unitMode;

    @Column(name = "fixed_unit_code", type = ColumnType.VARCHAR, length = 64, comment = "Fixed measure unit code")
    private String fixedUnitCode;

    @Column(name = "default_unit_code", type = ColumnType.VARCHAR, length = 64, comment = "Default measure unit code")
    private String defaultUnitCode;

    @Column(name = "unit_field_id", type = ColumnType.VARCHAR, length = 32, comment = "Companion unit metadata field id")
    private String unitFieldId;

    @Column(name = "base_value_field_id", type = ColumnType.VARCHAR, length = 32, comment = "Shadow base value metadata field id")
    private String baseValueFieldId;

    @Column(name = "base_unit_category_alias", type = ColumnType.VARCHAR, length = 64, comment = "Base measure unit category alias")
    private String baseUnitCategoryAlias;

    @Column(name = "base_unit_code", type = ColumnType.VARCHAR, length = 64, comment = "Base measure unit code")
    private String baseUnitCode;

    @Column(name = "unit_conversion_mode", type = ColumnType.VARCHAR, length = 32, comment = "Measure unit conversion mode")
    private FieldMeasureUnitConversionMode unitConversionMode;

    @Column(name = "conversion_scope_field_id", type = ColumnType.VARCHAR, length = 32, comment = "Measure conversion scope metadata field id")
    private String conversionScopeFieldId;

    @Column(name = "unit_required", type = ColumnType.BOOLEAN, comment = "Unit value required flag",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean unitRequired = Boolean.FALSE;

    @Column(name = "money_currency_mode", type = ColumnType.VARCHAR, length = 32, comment = "Money currency input mode")
    private FieldMoneyMode moneyCurrencyMode;

    @Column(name = "money_fixed_currency_code", type = ColumnType.VARCHAR, length = 3, comment = "Fixed money currency code")
    private String moneyFixedCurrencyCode;

    @Column(name = "money_default_currency_code", type = ColumnType.VARCHAR, length = 3, comment = "Default money currency code")
    private String moneyDefaultCurrencyCode;

    @Column(name = "money_currency_field_id", type = ColumnType.VARCHAR, length = 32, comment = "Companion money currency metadata field id")
    private String moneyCurrencyFieldId;

    @Column(name = "money_base_amount_field_id", type = ColumnType.VARCHAR, length = 32, comment = "Shadow money base amount metadata field id")
    private String moneyBaseAmountFieldId;

    @Column(name = "money_base_currency_code", type = ColumnType.VARCHAR, length = 3, comment = "Money base currency code")
    private String moneyBaseCurrencyCode;

    @Column(name = "money_rate_type_code", type = ColumnType.VARCHAR, length = 64, comment = "Money exchange rate type code")
    private String moneyRateTypeCode;

    @Column(name = "money_rate_date_field_id", type = ColumnType.VARCHAR, length = 32, comment = "Money rate date metadata field id")
    private String moneyRateDateFieldId;

    @Column(name = "money_exchange_rate_field_id", type = ColumnType.VARCHAR, length = 32, comment = "Shadow money exchange rate metadata field id")
    private String moneyExchangeRateFieldId;

    @Column(name = "money_currency_required", type = ColumnType.BOOLEAN, comment = "Money currency value required flag",
            defaultVal = @Default(bool = TrueOrFalse.TRUE))
    private Boolean moneyCurrencyRequired = Boolean.TRUE;
}
