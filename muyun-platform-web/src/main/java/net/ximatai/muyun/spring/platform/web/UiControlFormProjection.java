package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.ui.UiControlRule;
import java.util.*;
import java.util.function.BiFunction;

/** Uses the form's existing predicate runtime, scoped by the concrete editor slot. */
final class UiControlFormProjection {
    private UiControlFormProjection() {}
    record Element(String key, String label) {}
    record Form(String key, String title, List<Element> elements) {}
    static List<Form> forms(ResolvedModuleUiDescriptor descriptor) {
        List<Form> forms = new ArrayList<>();
        map(descriptor, (key, view) -> {
            if (view.viewKind() == ModuleViewKind.FORM) forms.add(new Form(key,
                    (view.title() == null ? view.viewCode() : view.title()) + " · " + slotTitle(key),
                    view.fields().stream().map(field -> new Element(elementKey(field),
                            field.label() == null ? elementKey(field) : field.label())).toList()));
            return view;
        });
        return List.copyOf(forms);
    }
    private static String slotTitle(String key) {
        if (key.equals("detail")) return "详情编辑";
        if (key.equals("default")) return "默认表单";
        return key.substring("surface:".length());
    }
    static ResolvedModuleUiDescriptor project(ResolvedModuleUiDescriptor descriptor, List<UiControlRule> rules) {
        if (rules.isEmpty()) return descriptor;
        return map(descriptor, (key, view) -> projectView(key, view, rules));
    }
    static ResolvedViewDescriptor projectView(String key, ResolvedViewDescriptor view, List<UiControlRule> rules) {
        return view.withFormulaProjection(view.fields().stream().map(field -> {
            UiRule<Boolean> visible = field.visible(), readOnly = field.readOnly();
            for (UiControlRule rule : rules) {
                if (!rule.enabled() || !key.equals(rule.formKey())) continue;
                for (UiControlRule.Target target : rule.targets()) {
                    if (!elementKey(field).equals(target.elementKey())) continue;
                    if (target.hide()) visible = combine(visible, rule.expression(), false);
                    if (target.readOnly()) readOnly = combine(readOnly, rule.expression(), true);
                }
            }
            return field.withUiState(visible, readOnly);
        }).toList(), view.formComputeRules());
    }
    static String elementKey(ResolvedViewFieldDescriptor field) {
        ViewFieldRef ref = field.fieldRef();
        return ref.relationCode() == null ? ref.fieldName() : ref.relationCode() + "." + ref.fieldName();
    }
    private static UiRule<Boolean> combine(UiRule<Boolean> base, String expression, boolean readOnly) {
        if (base.formula() == null) {
            boolean constant = Boolean.TRUE.equals(base.constant());
            if (constant == readOnly) return base;
            return new UiRule<>(null, UiFormula.booleanExpression(readOnly ? expression : "!(" + expression + ")"), base.disabledHint());
        }
        return new UiRule<>(null, UiFormula.booleanExpression("(" + base.formula().expression() + ")" +
                (readOnly ? " || (" : " && !(") + expression + ")"), base.disabledHint());
    }
    private static ResolvedModuleUiDescriptor map(ResolvedModuleUiDescriptor descriptor,
            BiFunction<String, ResolvedViewDescriptor, ResolvedViewDescriptor> mapper) {
        if (descriptor == null) return null;
        ResolvedModulePageDescriptor page = descriptor.page();
        if (page != null && page.detail() != null && page.detail().editor() != null) {
            var detail = page.detail();
            page = page.withDetail(new ResolvedPageDetailDescriptor(detail.emptyDescription(), detail.createTitle(),
                    detail.display(), mapper.apply("detail", detail.editor()), detail.workspaceView(), detail.showSystemInfo()));
        }
        var editor = descriptor.defaultEditor() == null ? null : mapper.apply("default", descriptor.defaultEditor());
        var surfaces = descriptor.editorSurfaces().stream().map(surface -> new ResolvedEditorSurfaceDescriptor(
                surface.key(), mapper.apply("surface:" + surface.key(), surface.editor()))).toList();
        return descriptor.withEditors(page, editor, surfaces);
    }
}
