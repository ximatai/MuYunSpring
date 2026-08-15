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
        return formSchema(definition, null, null);
    }

    public static FormSchema formSchema(ModuleUiDefinition definition, Class<?> modelClass) {
        return formSchema(definition, modelClass, null);
    }

    /** Resolves either the module page's detail editor or the explicitly named child resource editor. */
    public static FormSchema formSchema(ModuleUiDefinition definition, Class<?> modelClass, String resource) {
        if (definition == null) {
            return null;
        }
        ViewDefinition formView = formView(definition, resource);
        if (formView == null) {
            return null;
        }
        if (resource == null || resource.isBlank()) {
            validate(definition, formView, modelClass);
        }
        Map<String, FormValueType> valueTypes = fieldValueTypes(modelClass);
        FormDescriptor.Builder descriptor = FormDescriptor.builder(definition.moduleAlias())
                .title(formView.title());
        formView.fields().stream()
                .filter(field -> !Boolean.FALSE.equals(field.visible().constant()))
                .map(field -> field(field, valueTypes))
                .forEach(descriptor::field);
        return FormSchema.from(descriptor.build(), modelClass);
    }

    private static ViewDefinition formView(ModuleUiDefinition definition, String resource) {
        if (resource != null && !resource.isBlank()) {
            return definition.editorContributions().stream()
                    .filter(contribution -> resource.trim().equals(contribution.resource()))
                    .map(PageDetailEditorContribution::editor)
                    .findFirst()
                    .orElse(null);
        }
        ModulePageDefinition page = definition.page();
        if (page == null) return definition.customPageEditor();
        return switch (page) {
            case FlatManagementPageDefinition flat -> flat.detail().editor();
            case ListDetailCardPageDefinition card -> card.detail().editor();
            case null -> null;
        };
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
        ModuleUiDescriptorCompiler.validate(new ModuleUiDefinition(definition.moduleAlias(), List.of(), null,
                formView, List.of()), List.of(entity));
    }

    private static String entityAlias(String moduleAlias) {
        int lastSeparator = moduleAlias.lastIndexOf('.');
        return lastSeparator < 0 ? moduleAlias : moduleAlias.substring(lastSeparator + 1);
    }
}
