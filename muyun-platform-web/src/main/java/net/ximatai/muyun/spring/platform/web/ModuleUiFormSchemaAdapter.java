package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.form.FormControlType;
import net.ximatai.muyun.spring.ability.form.FormDescriptor;
import net.ximatai.muyun.spring.ability.form.FormField;
import net.ximatai.muyun.spring.ability.form.FormSchema;
import net.ximatai.muyun.spring.ability.form.FormValueType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.StaticEntityDefinitionCompiler;

import java.util.List;
import java.util.Map;

public final class ModuleUiFormSchemaAdapter {
    private ModuleUiFormSchemaAdapter() {
    }

    public static FormSchema formSchema(ModuleUiDefinition definition) {
        return formSchema(definition, null);
    }

    public static FormSchema formSchema(ModuleUiDefinition definition, Class<?> modelClass) {
        if (definition == null) {
            return null;
        }
        ViewDefinition formView = formView(definition.views());
        if (formView == null) {
            return null;
        }
        validate(definition, formView, modelClass);
        Map<String, FormValueType> valueTypes = fieldValueTypes(modelClass);
        FormDescriptor.Builder descriptor = FormDescriptor.builder(definition.moduleAlias())
                .title(formView.title());
        formView.fields().stream()
                .filter(field -> !Boolean.FALSE.equals(field.visible().constant()))
                .map(field -> field(field, valueTypes))
                .forEach(descriptor::field);
        return FormSchema.from(descriptor.build(), modelClass);
    }

    private static ViewDefinition formView(List<ViewDefinition> views) {
        return views.stream()
                .filter(view -> view.viewKind() == ModuleViewKind.FORM && "default_form".equals(view.viewCode()))
                .findFirst()
                .or(() -> views.stream().filter(view -> view.viewKind() == ModuleViewKind.FORM).findFirst())
                .orElse(null);
    }

    private static FormField field(ViewFieldDefinition field, Map<String, FormValueType> valueTypes) {
        FormValueType valueType = valueType(field, valueTypes);
        FormField formField = new FormField(
                field.fieldRef().fieldName(),
                field.label() == null ? field.fieldRef().fieldName() : field.label(),
                valueType,
                controlType(valueType),
                Boolean.TRUE.equals(field.required().constant()),
                Boolean.TRUE.equals(field.readOnly().constant()),
                null,
                null,
                null
        );
        return formField;
    }

    private static FormValueType valueType(ViewFieldDefinition field, Map<String, FormValueType> valueTypes) {
        FormValueType modelValueType = valueTypes.get(field.fieldRef().fieldName());
        if (modelValueType != null) {
            return modelValueType;
        }
        if ("enabledStatus".equals(field.uiType()) || "switch".equals(field.uiType())
                || "enabled".equals(field.fieldRef().fieldName())) {
            return FormValueType.BOOLEAN;
        }
        if ("textarea".equals(field.uiType())) {
            return FormValueType.TEXT;
        }
        return FormValueType.STRING;
    }

    private static Map<String, FormValueType> fieldValueTypes(Class<?> modelClass) {
        if (modelClass == null || modelClass == Object.class) {
            return Map.of();
        }
        return new StaticEntityDefinitionCompiler().compile("form", "form", modelClass).fields().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        field -> field.fieldName(),
                        field -> FormValueType.valueOf(field.type().name()),
                        (left, right) -> left
                ));
    }

    private static FormControlType controlType(FormValueType valueType) {
        return switch (valueType) {
            case BOOLEAN -> FormControlType.SWITCH;
            case TEXT -> FormControlType.TEXTAREA;
            default -> FormControlType.TEXT;
        };
    }

    private static void validate(ModuleUiDefinition definition, ViewDefinition formView, Class<?> modelClass) {
        if (modelClass == null || modelClass == Object.class) {
            return;
        }
        EntityDefinition entity = new StaticEntityDefinitionCompiler().compile(
                entityAlias(definition.moduleAlias()),
                definition.moduleAlias(),
                modelClass
        );
        ModuleUiDescriptorCompiler.validate(new ModuleUiDefinition(definition.moduleAlias(), List.of(formView)),
                List.of(entity));
    }

    private static String entityAlias(String moduleAlias) {
        int lastSeparator = moduleAlias.lastIndexOf('.');
        return lastSeparator < 0 ? moduleAlias : moduleAlias.substring(lastSeparator + 1);
    }
}
