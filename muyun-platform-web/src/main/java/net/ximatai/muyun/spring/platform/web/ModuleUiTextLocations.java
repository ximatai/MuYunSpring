package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit display slots only. Record values, formulas, field identities and control protocol properties are excluded. */
final class ModuleUiTextLocations {
    record Location(String path, String bindingKey, String originalText, boolean configured) { }
    private final List<Location> locations = new ArrayList<>();
    private boolean configured;

    static List<Location> of(ResolvedModuleUiDescriptor descriptor) {
        var visitor = new ModuleUiTextLocations();
        visitor.module(descriptor);
        return List.copyOf(visitor.locations);
    }

    static Map<String, TextReference> codeSources(ResolvedModuleUiDescriptor descriptor) {
        Map<String, TextReference> sources = new LinkedHashMap<>();
        for (var location : of(descriptor)) {
            if (location.configured()) continue;
            var reference = TextReference.code(location.bindingKey(), location.originalText());
            var previous = sources.putIfAbsent(location.bindingKey(), reference);
            if (previous != null && !previous.equals(reference)) throw new IllegalArgumentException("Conflicting UI text source: " + location.bindingKey());
        }
        return Map.copyOf(sources);
    }

    private void text(String path, String key, String property, String value) {
        if (value != null && !value.isBlank()) locations.add(new Location(path + "/" + property, key + "/" + property, value, configured));
    }

    private void module(ResolvedModuleUiDescriptor descriptor) {
        String key = "ui/" + descriptor.moduleAlias();
        text("", key, "title", descriptor.title());
        view(descriptor.defaultEditor(), "/defaultEditor", key + "/defaultEditor");
        for (int index = 0; index < descriptor.editorSurfaces().size(); index++) {
            var surface = descriptor.editorSurfaces().get(index);
            view(surface.editor(), "/editorSurfaces/" + index + "/editor", key + "/editorSurfaces/" + segment(surface.key()) + "/editor");
        }
        for (int index = 0; index < descriptor.editorContributions().size(); index++) {
            var contribution = descriptor.editorContributions().get(index);
            view(contribution.editor(), "/editorContributions/" + index + "/editor", key + "/editorContributions/" + segment(contribution.resource()) + "/editor");
        }
        var page = descriptor.page();
        if (page == null) return;
        String pageKey = key + "/page";
        var explorer = page.explorer();
        if (explorer != null) {
            String path = "/page/explorer", source = pageKey + "/explorer";
            text(path, source, "title", explorer.title());
            text(path, source, "searchPlaceholder", explorer.searchPlaceholder());
            text(path, source, "emptyDescription", explorer.emptyDescription());
            text(path, source, "recordLabel", explorer.recordLabel());
            text(path, source, "fallbackTitle", explorer.fallbackTitle());
        }
        if (page.navigator() != null) for (int index = 0; index < page.navigator().levels().size(); index++) {
            var level = page.navigator().levels().get(index);
            String path = "/page/navigator/levels/" + index, source = pageKey + "/navigator/levels/" + segment(level.key());
            text(path, source, "title", level.title());
            text(path, source, "searchPlaceholder", level.searchPlaceholder());
        }
        if (page.treeResource() != null) {
            var tree = page.treeResource();
            text("/page/treeResource", pageKey + "/treeResource", "title", tree.title());
            text("/page/treeResource", pageKey + "/treeResource", "emptyDescription", tree.emptyDescription());
            text("/page/treeResource", pageKey + "/treeResource", "createTitle", tree.createTitle());
        }
        if (page.list() != null) {
            var list = page.list();
            text("/page/list", pageKey + "/list", "searchPlaceholder", list.searchPlaceholder());
            view(list.fields(), "/page/list/fields", pageKey + "/list/fields");
            if (list.title() != null) text("/page/list/title", pageKey + "/list/title", "text", list.title().text());
            if (list.subtitle() != null) text("/page/list/subtitle", pageKey + "/list/subtitle", "text", list.subtitle().text());
        }
        if (page.detail() != null) {
            var detail = page.detail();
            text("/page/detail", pageKey + "/detail", "emptyDescription", detail.emptyDescription());
            text("/page/detail", pageKey + "/detail", "createTitle", detail.createTitle());
            view(detail.display(), "/page/detail/display", pageKey + "/detail/display");
            view(detail.editor(), "/page/detail/editor", pageKey + "/detail/editor");
        }
        for (int index = 0; index < page.actions().size(); index++) {
            var action = page.actions().get(index);
            String source = pageKey + "/actions/" + action.anchor().name() + "/" + segment(action.actionCode());
            text("/page/actions/" + index, source, "title", action.title());
        }
    }

    private void view(ResolvedViewDescriptor view, String path, String key) {
        if (view == null) return;
        boolean previous = configured;
        configured = view.sourceUiConfigId() != null;
        try {
            text(path, key, "title", view.title());
            for (int index = 0; index < view.fields().size(); index++) {
                var field = view.fields().get(index);
                var ref = field.fieldRef();
                String source = key + "/fields/" + (ref.relationCode() == null ? "main" : "relation/" + segment(ref.relationCode())) + "/" + segment(ref.fieldName());
                String fieldPath = path + "/fields/" + index;
                text(fieldPath, source, "label", field.label());
                text(fieldPath, source, "treeRootTitle", field.treeRootTitle());
                if (field.booleanStatus() != null) {
                    text(fieldPath + "/booleanStatus", source + "/booleanStatus", "trueLabel", field.booleanStatus().trueLabel());
                    text(fieldPath + "/booleanStatus", source + "/booleanStatus", "falseLabel", field.booleanStatus().falseLabel());
                }
            }
            for (int index = 0; index < view.formGroups().size(); index++) {
                var group = view.formGroups().get(index);
                String source = key + "/formGroups/" + segment(group.groupCode()), groupPath = path + "/formGroups/" + index;
                text(groupPath, source, "title", group.title());
                text(groupPath, source, "subtitle", group.subtitle());
            }
            for (int index = 0; index < view.formValidationRules().size(); index++) {
                var rule = view.formValidationRules().get(index);
                text(path + "/formValidationRules/" + index, key + "/formValidationRules/" + segment(rule.code()), "message", rule.message());
            }
        } finally {
            configured = previous;
        }
    }

    private static String segment(String value) { return value.replace("~", "~0").replace("/", "~1"); }
}
