package net.ximatai.muyun.spring.ability.form;

import net.ximatai.muyun.spring.common.option.OptionBinding;
import net.ximatai.muyun.spring.common.model.constraint.FieldInputRequirements;
import net.ximatai.muyun.spring.common.model.constraint.StaticFieldWriteRules;
import net.ximatai.muyun.spring.common.option.OptionFieldDefinition;
import net.ximatai.muyun.spring.common.option.OptionFieldResolver;
import net.ximatai.muyun.spring.common.option.OptionLoadResolver;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public record FormSchema(String scopeName,
                         String title,
                         List<Field> fields) {
    public FormSchema {
        if (scopeName == null || scopeName.isBlank()) {
            throw new IllegalArgumentException("form schema scope name must not be blank");
        }
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public static FormSchema from(FormDescriptor descriptor) {
        return from(descriptor, null);
    }

    public static FormSchema from(FormDescriptor descriptor, Class<?> modelClass) {
        if (descriptor == null) {
            throw new IllegalArgumentException("form descriptor must not be null");
        }
        var writeRules = StaticFieldWriteRules.resolve(modelClass);
        Map<String, OptionFieldDefinition> optionFields = optionFields(modelClass);
        Map<String, String> optionTitleFields = optionTitleFields(modelClass);
        return new FormSchema(
                descriptor.scopeName(),
                descriptor.title(),
                descriptor.fields().stream()
                        .map(field -> {
                            var binding = writeRules.get(field.fieldName());
                            return field.readOnly() || field.inputRequirements() != null || binding == null ? field
                                    : field.withInputRequirements(new FieldInputRequirements(
                                            binding.rules().requiredOnInsert(), binding.rules().requiredOnUpdate()));
                        })
                        .map(field -> Field.from(mergeOptionField(field, optionFields, optionTitleFields)))
                        .toList()
        );
    }

    private static FormField mergeOptionField(FormField field,
                                              Map<String, OptionFieldDefinition> optionFields,
                                              Map<String, String> optionTitleFields) {
        OptionFieldDefinition definition = optionFields.get(field.fieldName());
        FormField resolved = field.optionBinding() != null || definition == null
                ? field
                : field.withOptionField(definition);
        String optionTitleField = optionTitleFields.get(field.fieldName());
        return optionTitleField == null || resolved.optionTitleField() != null
                ? resolved
                : resolved.withOptionTitleField(optionTitleField);
    }

    private static Map<String, OptionFieldDefinition> optionFields(Class<?> modelClass) {
        if (modelClass == null) {
            return Map.of();
        }
        return OptionFieldResolver.resolve(modelClass).stream()
                .collect(Collectors.toMap(
                        OptionFieldDefinition::fieldName,
                        Function.identity(),
                        (first, ignored) -> first
                ));
    }

    private static Map<String, String> optionTitleFields(Class<?> modelClass) {
        if (modelClass == null) return Map.of();
        return OptionLoadResolver.resolve(modelClass).stream()
                .filter(definition -> "title".equals(definition.optionItemField()))
                .collect(Collectors.toMap(definition -> definition.sourceField(), definition -> definition.outputField(),
                        (first, ignored) -> first));
    }

    public record Field(String name,
                        String title,
                        FormValueType valueType,
                        FormControlType controlType,
                        boolean required,
                        boolean readOnly,
                        OptionBinding optionBinding,
                        OptionSelectionMode selectionMode,
                        String optionTitleField,
                        FieldInputRequirements inputRequirements) {
        public Field(String name, String title, FormValueType valueType, FormControlType controlType,
                     boolean required, boolean readOnly, OptionBinding optionBinding,
                     OptionSelectionMode selectionMode, String optionTitleField) {
            this(name, title, valueType, controlType, required, readOnly, optionBinding, selectionMode,
                    optionTitleField, null);
        }

        public Field {
            optionTitleField = optionTitleField == null || optionTitleField.isBlank() ? null : optionTitleField.trim();
        }

        static Field from(FormField field) {
            return new Field(
                    field.fieldName(),
                    field.title(),
                    field.valueType(),
                    field.controlType(),
                    field.required(),
                    field.readOnly(),
                    field.optionBinding(),
                    field.selectionMode(),
                    field.optionTitleField(),
                    field.inputRequirements()
            );
        }
    }
}
