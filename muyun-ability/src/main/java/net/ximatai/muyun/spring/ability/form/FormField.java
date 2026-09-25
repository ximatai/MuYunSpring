package net.ximatai.muyun.spring.ability.form;

import net.ximatai.muyun.spring.common.option.OptionBinding;
import net.ximatai.muyun.spring.common.model.constraint.FieldInputRequirements;
import net.ximatai.muyun.spring.common.option.OptionFieldDefinition;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;

public record FormField(String fieldName,
                        String title,
                        FormValueType valueType,
                        FormControlType controlType,
                        boolean required,
                        boolean readOnly,
                        OptionBinding optionBinding,
                        OptionSelectionMode selectionMode,
                        String optionTitleField,
                        FieldInputRequirements inputRequirements) {
    public FormField(String fieldName, String title, FormValueType valueType, FormControlType controlType,
                     boolean required, boolean readOnly, OptionBinding optionBinding,
                     OptionSelectionMode selectionMode, String optionTitleField) {
        this(fieldName, title, valueType, controlType, required, readOnly, optionBinding, selectionMode,
                optionTitleField, null);
    }

    public FormField {
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("form field name must not be blank");
        }
        title = title == null || title.isBlank() ? fieldName : title.trim();
        valueType = valueType == null ? FormValueType.STRING : valueType;
        selectionMode = optionBinding == null ? null
                : selectionMode == null ? OptionSelectionMode.SINGLE : selectionMode;
        controlType = controlType == null ? defaultControlType(valueType, optionBinding, selectionMode) : controlType;
        optionTitleField = optionBinding == null || optionTitleField == null || optionTitleField.isBlank()
                ? null : optionTitleField.trim();
    }

    public static FormField of(String fieldName) {
        return of(fieldName, FormValueType.STRING);
    }

    public static FormField of(String fieldName, FormValueType valueType) {
        return new FormField(fieldName, null, valueType, null, false, false, null, null, null);
    }

    public FormField withTitle(String title) {
        return new FormField(fieldName, title, valueType, controlType, required, readOnly,
                optionBinding, selectionMode, optionTitleField, inputRequirements);
    }

    public FormField asRequired() {
        return new FormField(fieldName, title, valueType, controlType, true, readOnly,
                optionBinding, selectionMode, optionTitleField, inputRequirements);
    }

    public FormField asReadOnly() {
        return new FormField(fieldName, title, valueType, controlType, required, true,
                optionBinding, selectionMode, optionTitleField, inputRequirements);
    }

    public FormField withControlType(FormControlType controlType) {
        return new FormField(fieldName, title, valueType, controlType, required, readOnly,
                optionBinding, selectionMode, optionTitleField, inputRequirements);
    }

    public FormField withOptionBinding(OptionBinding binding) {
        return withOptionBinding(binding, OptionSelectionMode.SINGLE);
    }

    public FormField withOptionBinding(OptionBinding binding, OptionSelectionMode selectionMode) {
        return new FormField(fieldName, title, valueType, null, required, readOnly,
                binding, selectionMode, null, inputRequirements);
    }

    public FormField withOptionField(OptionFieldDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("option field definition must not be null");
        }
        return new FormField(fieldName, title, valueType, null, required, readOnly,
                definition.binding(), definition.selectionMode(), null, inputRequirements);
    }

    public FormField withOptionTitleField(String optionTitleField) {
        return new FormField(fieldName, title, valueType, controlType, required, readOnly,
                optionBinding, selectionMode, optionTitleField, inputRequirements);
    }

    public FormField withInputRequirements(FieldInputRequirements requirements) {
        return new FormField(fieldName, title, valueType, controlType, required, readOnly,
                optionBinding, selectionMode, optionTitleField, requirements);
    }

    private static FormControlType defaultControlType(FormValueType valueType,
                                                      OptionBinding optionBinding,
                                                      OptionSelectionMode selectionMode) {
        if (optionBinding != null) {
            return selectionMode == OptionSelectionMode.MULTIPLE
                    ? FormControlType.MULTI_SELECT : FormControlType.SELECT;
        }
        return switch (valueType) {
            case STRING -> FormControlType.TEXT;
            case TEXT -> FormControlType.TEXTAREA;
            case INTEGER, LONG -> FormControlType.NUMBER;
            case DECIMAL -> FormControlType.DECIMAL;
            case BOOLEAN -> FormControlType.SWITCH;
            case DATE -> FormControlType.DATE;
            case DATETIME, INSTANT -> FormControlType.DATETIME;
            case JSON -> FormControlType.JSON;
        };
    }
}
