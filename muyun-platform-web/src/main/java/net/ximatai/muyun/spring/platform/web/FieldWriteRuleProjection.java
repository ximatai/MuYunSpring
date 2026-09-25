package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.model.constraint.FieldInputRequirements;

import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Projects model-owned write requirements only onto fields already exposed by an editor. */
final class FieldWriteRuleProjection {
    private FieldWriteRuleProjection() { }

    static Map<ViewFieldRef, FieldDefinition> fields(List<EntityDefinition> entities, String mainEntityAlias) {
        Map<ViewFieldRef, FieldDefinition> fields = new LinkedHashMap<>();
        for (var entity : entities) {
            String resource = entity.alias().equals(mainEntityAlias) ? null : entity.alias();
            entity.fields().forEach(field -> fields.put(new ViewFieldRef(resource, field.fieldName(), null), field));
        }
        return fields;
    }

    static ResolvedModuleUiDescriptor project(ResolvedModuleUiDescriptor descriptor,
                                               Map<ViewFieldRef, FieldDefinition> fields) {
        if (descriptor == null || fields.isEmpty()) return descriptor;
        var page = descriptor.page();
        if (page != null && page.detail() != null) {
            var detail = page.detail();
            detail = new ResolvedPageDetailDescriptor(detail.emptyDescription(), detail.createTitle(),
                    detail.display(), view(detail.editor(), fields), detail.workspaceView(), detail.showSystemInfo());
            page = new ResolvedModulePageDescriptor(page.template(), page.explorer(), page.navigator(), page.list(),
                    page.treeResource(), detail, page.traits(), page.quickSearchFields(), page.actions(), page.managedActions());
        }
        return descriptor.withEditors(page, view(descriptor.defaultEditor(), fields),
                        descriptor.editorSurfaces().stream().map(surface -> new ResolvedEditorSurfaceDescriptor(
                                surface.key(), view(surface.editor(), fields))).toList())
                .withEditorContributions(descriptor.editorContributions().stream().map(contribution ->
                        new ResolvedPageDetailEditorContribution(contribution.resource(), view(contribution.editor(), fields))).toList());
    }

    private static ResolvedViewDescriptor view(ResolvedViewDescriptor view, Map<ViewFieldRef, FieldDefinition> fields) {
        if (view == null || view.viewKind() != ModuleViewKind.FORM) return view;
        return view.withFields(view.fields().stream().map(field -> {
            var model = fields.get(new ViewFieldRef(field.fieldRef().relationCode(), field.fieldRef().fieldName(), null));
            if (model == null || !model.isPhysical() || model.behavior().writeProtected()
                    || Boolean.TRUE.equals(field.readOnly().constant())) return field;
            var rules = model.behavior().writeRules();
            // Server defaults satisfy insert requirements without requiring user input.
            boolean insert = rules.requiredOnInsert() && (model.behavior().defaultValue() == null
                    || model.behavior().defaultValue().isBlank());
            boolean update = rules.requiredOnUpdate();
            if (!insert && !update) return field;
            return field.withInputRequirements(new FieldInputRequirements(insert, update));
        }).toList());
    }
}
