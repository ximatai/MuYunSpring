package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.spring.common.model.constraint.FieldWriteRules;
import net.ximatai.muyun.spring.common.option.OptionBinding;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.security.FieldProtectionDefinition;

import java.util.Set;

public record FieldDefinition(
        String fieldName,
        String columnName,
        FieldType type,
        String name,
        boolean isRequired,
        boolean isUnique,
        boolean isIndexed,
        boolean isSortable,
        boolean isTitle,
        Integer length,
        Integer precision,
        Integer scale,
        FieldDictionaryBinding dictionaryBinding,
        FieldQueryDefinition queryDefinition,
        String defaultUiControlAlias,
        FieldBehaviorDefinition behavior,
        FieldProtectionDefinition protection,
        FieldMeasureUnitDefinition measureUnit,
        FieldMoneyDefinition money,
        FieldStorageForm storageForm,
        FieldValueShape valueShape,
        FieldOptionLoadDefinition optionLoad
) {
    public FieldDefinition(String fieldName,
                           String columnName,
                           FieldType type,
                           String name,
                           boolean isRequired,
                           boolean isUnique,
                           boolean isIndexed,
                           boolean isSortable,
                           boolean isTitle,
                           Integer length,
                           Integer precision,
                           Integer scale,
                           FieldDictionaryBinding dictionaryBinding,
                           FieldQueryDefinition queryDefinition,
                           String defaultUiControlAlias,
                           FieldBehaviorDefinition behavior,
                           FieldProtectionDefinition protection,
                           FieldMeasureUnitDefinition measureUnit,
                           FieldMoneyDefinition money,
                           FieldStorageForm storageForm,
                           FieldValueShape valueShape) {
        this(fieldName, columnName, type, name, isRequired, isUnique, isIndexed, isSortable, isTitle,
                length, precision, scale, dictionaryBinding, queryDefinition, defaultUiControlAlias, behavior, protection,
                measureUnit, money, storageForm, valueShape, null);
    }
    public FieldDefinition(String fieldName, String columnName, FieldType type, String name) {
        this(fieldName, columnName, type, name, false, false, false, false, false,
                null, null, null, null, null, null, null, null, null, null);
    }

    public FieldDefinition(String fieldName,
                           String columnName,
                           FieldType type,
                           String name,
                           boolean isRequired,
                           boolean isUnique,
                           boolean isIndexed,
                           boolean isSortable,
                           boolean isTitle,
                           Integer length,
                           Integer precision,
                           Integer scale,
                           FieldDictionaryBinding dictionaryBinding,
                           FieldQueryDefinition queryDefinition) {
        this(fieldName, columnName, type, name, isRequired, isUnique, isIndexed, isSortable, isTitle,
                length, precision, scale, dictionaryBinding, queryDefinition, null, null, null, null, null);
    }

    public FieldDefinition(String fieldName,
                           String columnName,
                           FieldType type,
                           String name,
                           boolean isRequired,
                           boolean isUnique,
                           boolean isIndexed,
                           boolean isSortable,
                           boolean isTitle,
                           Integer length,
                           Integer precision,
                           Integer scale,
                           FieldDictionaryBinding dictionaryBinding,
                           FieldQueryDefinition queryDefinition,
                           String defaultUiControlAlias,
                           FieldBehaviorDefinition behavior,
                           FieldProtectionDefinition protection,
                           FieldMeasureUnitDefinition measureUnit,
                           FieldMoneyDefinition money) {
        this(fieldName, columnName, type, name, isRequired, isUnique, isIndexed, isSortable, isTitle,
                length, precision, scale, dictionaryBinding, queryDefinition, defaultUiControlAlias, behavior, protection,
                measureUnit, money, FieldStorageForm.PHYSICAL);
    }

    public FieldDefinition {
        queryDefinition = queryDefinition == null ? FieldQueryDefinition.disabled() : queryDefinition;
        behavior = behavior == null ? FieldBehaviorDefinition.DEFAULT : behavior;
        protection = protection == null ? FieldProtectionDefinition.NONE : protection;
        measureUnit = measureUnit == null ? FieldMeasureUnitDefinition.NONE : measureUnit;
        money = money == null ? FieldMoneyDefinition.NONE : money;
        storageForm = storageForm == null ? FieldStorageForm.PHYSICAL : storageForm;
        valueShape = valueShape == null ? FieldValueShape.DEFAULT : valueShape;
    }

    public FieldDefinition(String fieldName,
                           String columnName,
                           FieldType type,
                           String name,
                           boolean isRequired,
                           boolean isUnique,
                           boolean isIndexed,
                           boolean isSortable,
                           boolean isTitle,
                           Integer length,
                           Integer precision,
                           Integer scale,
                           FieldDictionaryBinding dictionaryBinding,
                           FieldQueryDefinition queryDefinition,
                           String defaultUiControlAlias,
                           FieldBehaviorDefinition behavior,
                           FieldProtectionDefinition protection,
                           FieldMeasureUnitDefinition measureUnit,
                           FieldMoneyDefinition money,
                           FieldStorageForm storageForm) {
        this(fieldName, columnName, type, name, isRequired, isUnique, isIndexed, isSortable, isTitle,
                length, precision, scale, dictionaryBinding, queryDefinition, defaultUiControlAlias, behavior, protection,
                measureUnit, money, storageForm, FieldValueShape.DEFAULT);
    }

    public static FieldDefinition of(String fieldName, FieldType type, String name) {
        return new FieldDefinition(fieldName, fieldName, type, name);
    }

    public static FieldDefinition string(String fieldName, String name) {
        return of(fieldName, FieldType.STRING, name);
    }

    public static FieldDefinition text(String fieldName, String name) {
        return of(fieldName, FieldType.TEXT, name);
    }

    public static FieldDefinition integer(String fieldName, String name) {
        return of(fieldName, FieldType.INTEGER, name);
    }

    public static FieldDefinition longInteger(String fieldName, String name) {
        return of(fieldName, FieldType.LONG, name);
    }

    public static FieldDefinition decimal(String fieldName, String name) {
        return of(fieldName, FieldType.DECIMAL, name);
    }

    public static FieldDefinition bool(String fieldName, String name) {
        return of(fieldName, FieldType.BOOLEAN, name);
    }

    public static FieldDefinition timestamp(String fieldName, String name) {
        return of(fieldName, FieldType.TIMESTAMP, name);
    }

    public static FieldDefinition zonedTimestamp(String fieldName, String name) {
        return of(fieldName, FieldType.ZONED_TIMESTAMP, name);
    }

    public static FieldDefinition zonedTimestampTimeZone(String zonedTimestampFieldName, String zonedTimestampColumnName) {
        return string(
                FieldCompanionRules.zonedTimestampTimeZoneFieldName(zonedTimestampFieldName),
                "Time Zone"
        ).column(FieldCompanionRules.zonedTimestampTimeZoneColumnName(zonedTimestampColumnName)).length(64);
    }

    public static FieldDefinition parentId() {
        return string(PlatformAbilityFields.TREE_PARENT_FIELD, "Parent")
                .column(PlatformAbilityFields.TREE_PARENT_COLUMN)
                .length(PlatformAbilityFields.TREE_PARENT_LENGTH);
    }

    public static FieldDefinition sortOrder() {
        return integer(PlatformAbilityFields.SORT_FIELD, "Sort Order")
                .column(PlatformAbilityFields.SORT_COLUMN)
                .sortable();
    }

    public static FieldDefinition titleField() {
        return string(PlatformAbilityFields.TITLE_FIELD, "Title")
                .column(PlatformAbilityFields.TITLE_COLUMN)
                .length(PlatformAbilityFields.TITLE_LENGTH)
                .title();
    }

    public static FieldDefinition enabled() {
        return bool(PlatformAbilityFields.ENABLED_FIELD, "Enabled")
                .column(PlatformAbilityFields.ENABLED_COLUMN);
    }

    public String code() {
        return fieldName;
    }

    public FieldDefinition column(String value) {
        return new FieldDefinition(fieldName, value, type, name, isRequired, isUnique, isIndexed, isSortable, isTitle,
                length, precision, scale, dictionaryBinding, queryDefinition, defaultUiControlAlias, behavior, protection,
                measureUnit, money, storageForm, valueShape, optionLoad);
    }

    public FieldDefinition required() {
        return new FieldDefinition(fieldName, columnName, type, name, true, isUnique, isIndexed, isSortable, isTitle,
                length, precision, scale, dictionaryBinding, queryDefinition, defaultUiControlAlias, behavior, protection,
                measureUnit, money, storageForm, valueShape, optionLoad);
    }

    public FieldDefinition unique() {
        return new FieldDefinition(fieldName, columnName, type, name, isRequired, true, isIndexed, isSortable, isTitle,
                length, precision, scale, dictionaryBinding, queryDefinition, defaultUiControlAlias, behavior, protection,
                measureUnit, money, storageForm, valueShape, optionLoad);
    }

    public FieldDefinition indexed() {
        return new FieldDefinition(fieldName, columnName, type, name, isRequired, isUnique, true, isSortable, isTitle,
                length, precision, scale, dictionaryBinding, queryDefinition, defaultUiControlAlias, behavior, protection,
                measureUnit, money, storageForm, valueShape, optionLoad);
    }

    public FieldDefinition sortable() {
        return new FieldDefinition(fieldName, columnName, type, name, isRequired, isUnique, isIndexed, true, isTitle,
                length, precision, scale, dictionaryBinding, queryDefinition, defaultUiControlAlias, behavior, protection,
                measureUnit, money, storageForm, valueShape, optionLoad);
    }

    public FieldDefinition title() {
        return new FieldDefinition(fieldName, columnName, type, name, isRequired, isUnique, isIndexed, isSortable, true,
                length, precision, scale, dictionaryBinding, queryDefinition, defaultUiControlAlias, behavior, protection,
                measureUnit, money, storageForm, valueShape, optionLoad);
    }

    public FieldDefinition length(int value) {
        return new FieldDefinition(fieldName, columnName, type, name, isRequired, isUnique, isIndexed, isSortable, isTitle,
                value, precision, scale, dictionaryBinding, queryDefinition, defaultUiControlAlias, behavior, protection,
                measureUnit, money, storageForm, valueShape, optionLoad);
    }

    public FieldDefinition precision(int value, int scaleValue) {
        return new FieldDefinition(fieldName, columnName, type, name, isRequired, isUnique, isIndexed, isSortable, isTitle,
                length, value, scaleValue, dictionaryBinding, queryDefinition, defaultUiControlAlias, behavior, protection,
                measureUnit, money, storageForm, valueShape, optionLoad);
    }

    public FieldDefinition dictionary(String applicationAlias, String categoryAlias) {
        return dictionary(applicationAlias, categoryAlias, OptionSelectionMode.SINGLE);
    }

    public FieldDefinition dictionary(String applicationAlias, String categoryAlias, OptionSelectionMode selectionMode) {
        return new FieldDefinition(fieldName, columnName, type, name, isRequired, isUnique, isIndexed, isSortable, isTitle,
                length, precision, scale, new FieldDictionaryBinding(applicationAlias, categoryAlias, selectionMode),
                queryDefinition, defaultUiControlAlias, behavior, protection, measureUnit, money, storageForm, valueShape,
                optionLoad);
    }

    public FieldDefinition queryable() {
        return queryable(FieldQueryDefinition.enabled(type));
    }

    public FieldDefinition queryable(DynamicQueryOperator defaultOperator, Set<DynamicQueryOperator> operators) {
        return queryable(FieldQueryDefinition.enabled(type, defaultOperator, operators));
    }

    public FieldDefinition queryable(FieldQueryDefinition value) {
        return new FieldDefinition(fieldName, columnName, type, name, isRequired, isUnique, isIndexed, isSortable, isTitle,
                length, precision, scale, dictionaryBinding, value, defaultUiControlAlias, behavior, protection, measureUnit,
                money, storageForm, valueShape, optionLoad);
    }

    public FieldDefinition defaultUiType(String value) {
        return new FieldDefinition(fieldName, columnName, type, name, isRequired, isUnique, isIndexed, isSortable, isTitle,
                length, precision, scale, dictionaryBinding, queryDefinition, value, behavior, protection, measureUnit,
                money, storageForm, valueShape, optionLoad);
    }

    public FieldDefinition defaultValue(String value) {
        return behavior(new FieldBehaviorDefinition(value, behavior.validationRegex(), behavior.copyable(), behavior.writeProtected(), behavior.writeRules()));
    }

    public FieldDefinition validationRegex(String value) {
        return behavior(new FieldBehaviorDefinition(behavior.defaultValue(), value, behavior.copyable(), behavior.writeProtected(), behavior.writeRules()));
    }

    public FieldDefinition notCopyable() {
        return behavior(new FieldBehaviorDefinition(behavior.defaultValue(), behavior.validationRegex(), false, behavior.writeProtected(), behavior.writeRules()));
    }

    public FieldDefinition writeProtected() {
        return behavior(new FieldBehaviorDefinition(behavior.defaultValue(), behavior.validationRegex(), behavior.copyable(), true, behavior.writeRules()));
    }

    /** Save-time requirements; storage nullability and explicit non-blank rules remain distinct facts. */
    public FieldWriteRules resolvedWriteRules() {
        return behavior.writeRules().withNonNull(isRequired);
    }

    public FieldDefinition writeRules(FieldWriteRules value) {
        return behavior(new FieldBehaviorDefinition(behavior.defaultValue(), behavior.validationRegex(),
                behavior.copyable(), behavior.writeProtected(), value));
    }

    public FieldDefinition behavior(FieldBehaviorDefinition value) {
        return new FieldDefinition(fieldName, columnName, type, name, isRequired, isUnique, isIndexed, isSortable, isTitle,
                length, precision, scale, dictionaryBinding, queryDefinition, defaultUiControlAlias, value, protection,
                measureUnit, money, storageForm, valueShape, optionLoad);
    }

    public FieldDefinition protection(FieldProtectionDefinition value) {
        return new FieldDefinition(fieldName, columnName, type, name, isRequired, isUnique, isIndexed, isSortable, isTitle,
                length, precision, scale, dictionaryBinding, queryDefinition, defaultUiControlAlias, behavior, value,
                measureUnit, money, storageForm, valueShape, optionLoad);
    }

    public FieldDefinition measureUnit(FieldMeasureUnitDefinition value) {
        return new FieldDefinition(fieldName, columnName, type, name, isRequired, isUnique, isIndexed, isSortable, isTitle,
                length, precision, scale, dictionaryBinding, queryDefinition, defaultUiControlAlias, behavior, protection,
                value, money, storageForm, valueShape, optionLoad);
    }

    public FieldDefinition money(FieldMoneyDefinition value) {
        return new FieldDefinition(fieldName, columnName, type, name, isRequired, isUnique, isIndexed, isSortable, isTitle,
                length, precision, scale, dictionaryBinding, queryDefinition, defaultUiControlAlias, behavior, protection,
                measureUnit, value, storageForm, valueShape, optionLoad);
    }

    public OptionBinding optionBinding() {
        return dictionaryBinding == null ? null : dictionaryBinding.toOptionBinding();
    }

    public boolean isPhysical() {
        return storageForm == FieldStorageForm.PHYSICAL;
    }

    public FieldDefinition physical() {
        return storageForm(FieldStorageForm.PHYSICAL);
    }

    public FieldDefinition virtual() {
        return storageForm(FieldStorageForm.VIRTUAL);
    }

    public FieldDefinition storageForm(FieldStorageForm value) {
        return new FieldDefinition(fieldName, columnName, type, name, isRequired, isUnique, isIndexed, isSortable, isTitle,
                length, precision, scale, dictionaryBinding, queryDefinition, defaultUiControlAlias, behavior, protection,
                measureUnit, money, value, valueShape, optionLoad);
    }

    public FieldDefinition valueShape(FieldValueShape value) {
        return new FieldDefinition(fieldName, columnName, type, name, isRequired, isUnique, isIndexed, isSortable, isTitle,
                length, precision, scale, dictionaryBinding, queryDefinition, defaultUiControlAlias, behavior, protection,
                measureUnit, money, storageForm, value, optionLoad);
    }

    public FieldDefinition jsonSet() {
        return valueShape(FieldValueShape.JSON_SET);
    }

    /**
     * Loads a stable OptionItem property from a dictionary-bound source field into this virtual field.
     */
    public FieldDefinition optionLoad(String sourceField) {
        return optionLoad(sourceField, "title");
    }

    public FieldDefinition optionLoad(String sourceField, String optionItemField) {
        return new FieldDefinition(fieldName, columnName, type, name, isRequired, isUnique, isIndexed, isSortable, isTitle,
                length, precision, scale, dictionaryBinding, queryDefinition, defaultUiControlAlias, behavior, protection,
                measureUnit, money, storageForm, valueShape, new FieldOptionLoadDefinition(sourceField, optionItemField));
    }
}
