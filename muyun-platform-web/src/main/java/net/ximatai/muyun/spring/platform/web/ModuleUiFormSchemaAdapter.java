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
        return formSchema(definition, null, null, null);
    }

    public static FormSchema formSchema(ModuleUiDefinition definition, Class<?> modelClass) {
        return formSchema(definition, modelClass, null, null);
    }

    /** Resolves a page detail editor, module editor surface, or explicitly named child resource editor. */
    public static FormSchema formSchema(ModuleUiDefinition definition, Class<?> modelClass, String resource) {
        return formSchema(definition, modelClass, resource, null);
    }

    public static FormSchema formSchema(ModuleUiDefinition definition, Class<?> modelClass, String resource,
                                        String editorSurface) {
        if (definition == null) {
            return null;
        }
        ViewDefinition formView = formView(definition, resource, editorSurface);
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

    /** Builds the legacy form protocol from compiled, source-neutral UI facts. */
    public static FormSchema formSchema(ResolvedModuleUiDescriptor descriptor, Class<?> modelClass, String resource,
                                        String editorSurface) {
        if (descriptor == null) return null;
        ResolvedViewDescriptor formView = resolvedFormView(descriptor, resource, editorSurface);
        if (formView == null) return null;
        FormDescriptor.Builder form = FormDescriptor.builder(descriptor.moduleAlias()).title(formView.title());
        formView.fields().stream()
                .filter(field -> !Boolean.FALSE.equals(field.visible().constant()))
                .map(ModuleUiFormSchemaAdapter::field)
                .forEach(form::field);
        return FormSchema.from(form.build(), modelClass);
    }

    private static ViewDefinition formView(ModuleUiDefinition definition, String resource, String editorSurface) {
        if (resource != null && !resource.isBlank()) {
            return definition.editorContributions().stream()
                    .filter(contribution -> resource.trim().equals(contribution.resource()))
                    .map(PageDetailEditorContribution::editor)
                    .findFirst()
                    .orElse(null);
        }
        if (editorSurface != null && !editorSurface.isBlank()) {
            return definition.editorSurfaces().stream()
                    .filter(surface -> editorSurface.trim().equals(surface.key()))
                    .map(EditorSurfaceDefinition::editor)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("editor surface is not declared: " + editorSurface));
        }
        ModulePageDefinition page = definition.page();
        if (page == null) return definition.defaultEditor();
        return switch (page) {
            case FlatManagementPageDefinition flat -> flat.detail().editor();
            case ListDetailCardPageDefinition card -> card.detail().editor();
            case TreeManagementPageDefinition tree -> tree.detail().editor();
            case null -> null;
        };
    }

    private static ResolvedViewDescriptor resolvedFormView(ResolvedModuleUiDescriptor descriptor, String resource,
                                                           String editorSurface) {
        if (resource != null && !resource.isBlank()) {
            return descriptor.editorContributions().stream()
                    .filter(contribution -> resource.trim().equals(contribution.resource()))
                    .map(ResolvedPageDetailEditorContribution::editor).findFirst().orElse(null);
        }
        if (editorSurface != null && !editorSurface.isBlank()) {
            return descriptor.editorSurfaces().stream()
                    .filter(surface -> editorSurface.trim().equals(surface.key()))
                    .map(ResolvedEditorSurfaceDescriptor::editor).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("editor surface is not declared: " + editorSurface));
        }
        if (descriptor.page() != null) return descriptor.page().detail().editor();
        return descriptor.defaultEditor();
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

    private static FormField field(ResolvedViewFieldDescriptor field) {
        FormValueType valueType = field.valueType() == null ? FormValueType.STRING
                : switch (field.valueType()) {
                    case TIMESTAMP, ZONED_TIMESTAMP -> FormValueType.INSTANT;
                    default -> FormValueType.valueOf(field.valueType().name());
                };
        FormField result = new FormField(field.fieldRef().fieldName(), field.label(), valueType, controlType(field),
                Boolean.TRUE.equals(field.required().constant()), Boolean.TRUE.equals(field.readOnly().constant()),
                field.option() == null ? null : field.option().binding(),
                field.option() == null ? null : field.option().selectionMode(),
                field.option() == null ? null : field.option().titleField());
        return result;
    }

    private static FormControlType controlType(ResolvedViewFieldDescriptor field) {
        if (field.fieldControl() == null) return null;
        return switch (field.fieldControl().rendererType()) {
            case "TEXT" -> FormControlType.TEXT;
            case "TEXTAREA" -> FormControlType.TEXTAREA;
            case "NUMBER" -> FormControlType.NUMBER;
            case "DECIMAL" -> FormControlType.DECIMAL;
            case "SWITCH", "ENABLED_STATUS" -> FormControlType.SWITCH;
            case "DATE" -> FormControlType.DATE;
            case "DATETIME" -> FormControlType.DATETIME;
            case "SELECT" -> FormControlType.SELECT;
            case "MULTI_SELECT" -> FormControlType.MULTI_SELECT;
            case "JSON" -> FormControlType.JSON;
            default -> null;
        };
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
        java.util.LinkedHashMap<String, FormValueType> result = new StaticEntityDefinitionCompiler()
                .compile("form", "form", modelClass).fields().stream()
                .collect(java.util.stream.Collectors.toMap(
                        field -> field.fieldName(),
                        field -> FormValueType.valueOf(field.type().name()),
                        (left, right) -> left,
                        java.util.LinkedHashMap::new
                ));
        StaticWriteOnlyInputFields.resolve(modelClass).forEach((fieldName, type) ->
                result.putIfAbsent(fieldName, FormValueType.valueOf(type.name())));
        return Map.copyOf(result);
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
                        formView, List.of(), List.of()), List.of(entity),
                StaticWriteOnlyInputFields.resolve(modelClass).keySet());
    }

    private static String entityAlias(String moduleAlias) {
        int lastSeparator = moduleAlias.lastIndexOf('.');
        return lastSeparator < 0 ? moduleAlias : moduleAlias.substring(lastSeparator + 1);
    }
}
