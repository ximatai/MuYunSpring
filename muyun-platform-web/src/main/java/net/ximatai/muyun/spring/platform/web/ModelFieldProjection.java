package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.model.constraint.FieldInputRequirements;
import net.ximatai.muyun.spring.common.model.constraint.StaticFieldWriteRules;
import net.ximatai.muyun.spring.common.model.constraint.TextNormalization;

import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Projects model-owned content policies and write requirements onto already exposed fields. */
final class ModelFieldProjection {
    private ModelFieldProjection() { }

    static Map<ViewFieldRef, FieldDefinition> fields(List<EntityDefinition> entities, String mainEntityAlias) {
        Map<ViewFieldRef, FieldDefinition> fields = new LinkedHashMap<>();
        for (var entity : entities) {
            String resource = entity.alias().equals(mainEntityAlias) ? null : entity.alias();
            entity.fields().forEach(field -> fields.put(new ViewFieldRef(resource, field.fieldName(), null), field));
        }
        return fields;
    }

    static Map<ViewFieldRef, net.ximatai.muyun.spring.ability.code.CodeFieldDefinition> codeFields(List<EntityDefinition> entities, String mainEntityAlias) {
        Map<ViewFieldRef, net.ximatai.muyun.spring.ability.code.CodeFieldDefinition> fields = new LinkedHashMap<>();
        for (var entity : entities) {
            String resource = entity.alias().equals(mainEntityAlias) ? null : entity.alias();
            entity.businessCodes().forEach((name, declaration) -> fields.put(new ViewFieldRef(resource, name, null), declaration));
        }
        return fields;
    }

    static Map<ViewFieldRef, FieldDefinition> staticFields(StaticModuleDefinition definition) {
        var fields = fields(definition.entities(), definition.entities().isEmpty() ? null : definition.entities().getFirst().alias());
        Class<?> modelClass = definition.modelClass();
        if (modelClass == null) return fields;
        // Standard identity fields are omitted from dynamic entity business fields. The standard
        // alias editor addresses the model ID, so preserve its input constraints and write rules.
        var id = org.springframework.util.ReflectionUtils.findField(modelClass, "id");
        var column = id == null ? null : id.getAnnotation(net.ximatai.muyun.database.core.annotation.Column.class);
        if (column != null && column.length() > 0
                && org.springframework.beans.BeanUtils.getPropertyDescriptor(modelClass, "alias") != null) {
            var alias = FieldDefinition.string("alias", "alias").length(column.length());
            var binding = StaticFieldWriteRules.resolve(modelClass).get("id");
            if (binding != null) alias = alias.writeRules(binding.rules());
            var pattern = id.getAnnotation(net.ximatai.muyun.spring.common.model.constraint.FieldPattern.class);
            if (pattern != null) alias = alias.validationRegex(pattern.value());
            fields.putIfAbsent(new ViewFieldRef(null, "alias", null), alias);
        }
        return fields;
    }

    static ResolvedModuleUiDescriptor project(ResolvedModuleUiDescriptor descriptor,
                                               Map<ViewFieldRef, FieldDefinition> fields) {
        return project(descriptor, fields, Map.of());
    }
    static ResolvedModuleUiDescriptor project(ResolvedModuleUiDescriptor descriptor,
                                               Map<ViewFieldRef, FieldDefinition> fields,
                                               Map<ViewFieldRef, net.ximatai.muyun.spring.ability.code.CodeFieldDefinition> codes) {
        if (descriptor == null || fields.isEmpty()) return descriptor;
        var page = descriptor.page();
        if (page != null) {
            var detail = page.detail();
            if (detail != null) detail = new ResolvedPageDetailDescriptor(detail.emptyDescription(), detail.createTitle(),
                    view(detail.display(), fields, codes), view(detail.editor(), fields, codes), detail.workspaceView(), detail.showSystemInfo());
            var list = page.list();
            if (list != null) list = new ResolvedPageListDescriptor(list.searchPlaceholder(), view(list.fields(), fields, codes),
                    list.title(), list.subtitle(), list.relationExpansions(), list.persistentQueryControls(), list.querySummaries());
            page = new ResolvedModulePageDescriptor(page.template(), page.explorer(), page.navigator(), list,
                    page.treeResource(), detail, page.traits(), page.quickSearchFields(), page.actions(), page.managedActions());
        }
        return descriptor.withEditors(page, view(descriptor.defaultEditor(), fields, codes),
                        descriptor.editorSurfaces().stream().map(surface -> new ResolvedEditorSurfaceDescriptor(
                                surface.key(), view(surface.editor(), fields, codes))).toList())
                .withEditorContributions(descriptor.editorContributions().stream().map(contribution ->
                        new ResolvedPageDetailEditorContribution(contribution.resource(), view(contribution.editor(), fields, codes))).toList());
    }

    private static ResolvedViewDescriptor view(ResolvedViewDescriptor view, Map<ViewFieldRef, FieldDefinition> fields, Map<ViewFieldRef, net.ximatai.muyun.spring.ability.code.CodeFieldDefinition> codes) {
        if (view == null) return null;
        return view.withFields(view.fields().stream().map(field -> {
            var model = fields.get(new ViewFieldRef(field.fieldRef().relationCode(), field.fieldRef().fieldName(), null));
            if (model == null) return field;
            field = field.withContent(model.content()).withBusinessCode(codes.get(new ViewFieldRef(field.fieldRef().relationCode(), field.fieldRef().fieldName(), null)));
            if (view.viewKind() != ModuleViewKind.FORM || !model.isPhysical() || model.behavior().writeProtected()
                    || Boolean.TRUE.equals(field.readOnly().constant())) return field;
            var rules = model.behavior().writeRules();
            // Server defaults satisfy insert requirements without requiring user input.
            boolean insert = rules.requiredOnInsert() && (model.behavior().defaultValue() == null
                    || model.behavior().defaultValue().isBlank());
            boolean update = rules.requiredOnUpdate();
            // TEXT has no VARCHAR capacity, even when a legacy declaration carries a length hint.
            Integer maxLength = model.type() == FieldType.STRING ? model.length() : null;
            Integer precision = model.type() == FieldType.DECIMAL ? model.precision() : null;
            Integer scale = model.type() == FieldType.DECIMAL ? model.scale() : null;
            String regex = model.behavior().validationRegex();
            if (!insert && !update && maxLength == null && precision == null && scale == null && regex == null
                    && rules.textNormalization() == TextNormalization.NONE) return field;
            return field.withInputRequirements(new FieldInputRequirements(insert, update, maxLength, precision, scale, regex, rules.textNormalization()));
        }).toList());
    }
}
