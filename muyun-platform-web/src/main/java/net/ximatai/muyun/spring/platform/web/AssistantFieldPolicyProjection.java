package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.security.StaticFieldProtectionResolver;
import net.ximatai.muyun.spring.common.security.AssistantFieldPolicy;

import java.util.LinkedHashSet;
import java.util.Set;

/** Applies model-owned protection ceilings equally to static and dynamic page descriptors. */
final class AssistantFieldPolicyProjection {
    private AssistantFieldPolicyProjection() { }

    static ResolvedModuleUiDescriptor protectStatic(ResolvedModuleUiDescriptor descriptor,
                                                     StaticModuleDefinition definition) {
        Set<ViewFieldRef> protectedFields = new LinkedHashSet<>();
        addStatic(protectedFields, null, definition.modelClass());
        definition.entityModelClasses().forEach((resource, type) -> addStatic(protectedFields, resource, type));
        return protect(descriptor, protectedFields);
    }

    private static void addStatic(Set<ViewFieldRef> fields, String resource, Class<?> type) {
        StaticFieldProtectionResolver.resolve(type).fields().forEach(field ->
                fields.add(new ViewFieldRef(resource, field.fieldName(), null)));
    }

    static ResolvedModuleUiDescriptor protect(ResolvedModuleUiDescriptor descriptor, Set<ViewFieldRef> fields) {
        if (descriptor == null || fields.isEmpty()) return descriptor;
        var page = descriptor.page();
        if (page != null) {
            var detail = page.detail();
            if (detail != null) detail = new ResolvedPageDetailDescriptor(detail.emptyDescription(), detail.createTitle(),
                    view(detail.display(), fields), view(detail.editor(), fields), detail.workspaceView(), detail.showSystemInfo());
            var list = page.list();
            if (list != null) list = new ResolvedPageListDescriptor(list.searchPlaceholder(), view(list.fields(), fields),
                    list.title(), list.subtitle(), list.relationExpansions(), list.persistentQueryControls(), list.querySummaries());
            page = new ResolvedModulePageDescriptor(page.template(), page.explorer(), page.navigator(), list,
                    page.treeResource(), detail, page.traits(), page.quickSearchFields(), page.actions(), page.managedActions());
        }
        return descriptor.withEditors(page, view(descriptor.defaultEditor(), fields),
                        descriptor.editorSurfaces().stream().map(surface -> new ResolvedEditorSurfaceDescriptor(
                                surface.key(), view(surface.editor(), fields))).toList())
                .withEditorContributions(descriptor.editorContributions().stream().map(contribution ->
                        new ResolvedPageDetailEditorContribution(contribution.resource(), view(contribution.editor(), fields))).toList());
    }

    private static ResolvedViewDescriptor view(ResolvedViewDescriptor view, Set<ViewFieldRef> fields) {
        if (view == null) return null;
        return view.withFormulaProjection(view.fields().stream().map(field -> fields.contains(new ViewFieldRef(field.fieldRef().relationCode(), field.fieldRef().fieldName(), null))
                && field.assistantPolicy() != AssistantFieldPolicy.HIDDEN
                ? field.withAssistantPolicy(AssistantFieldPolicy.DESCRIBE) : field).toList(), view.formComputeRules());
    }
}
