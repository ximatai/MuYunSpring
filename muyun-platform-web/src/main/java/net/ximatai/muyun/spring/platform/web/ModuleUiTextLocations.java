package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit display slots only. Record values, formulas, field identities and control protocol properties are excluded. */
final class ModuleUiTextLocations {
    record Location(String path, String bindingKey, String originalText, boolean configured, TextReference declaredSource) {
        Location(String path, String bindingKey, String originalText, boolean configured) { this(path, bindingKey, originalText, configured, null); }
    }
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
            var reference = location.declaredSource() == null ? TextReference.code(location.bindingKey(), location.originalText()) : location.declaredSource();
            var previous = sources.putIfAbsent(location.bindingKey(), reference);
            if (previous != null && !previous.equals(reference)) throw new IllegalArgumentException("Conflicting UI text source: " + location.bindingKey());
        }
        return Map.copyOf(sources);
    }

    static ResolvedModuleUiDescriptor withConfiguredSources(ResolvedModuleUiDescriptor descriptor, boolean configurationRoot) {
        if (descriptor == null) return null;
        Map<String, TextReference> sources = new LinkedHashMap<>(descriptor.sourceTexts());
        Map<String, TextReference> configuredSources = new LinkedHashMap<>();
        for (var location : of(descriptor)) {
            if (location.declaredSource() != null) {
                sources.put(location.bindingKey(), location.declaredSource());
                continue;
            }
            if (configurationRoot || location.configured()) {
                var reference = TextReference.configured("config/" + location.bindingKey(), location.originalText());
                var previous = configuredSources.putIfAbsent(location.bindingKey(), reference);
                if (previous != null && !previous.equals(reference)) throw new IllegalArgumentException("Conflicting UI text source: " + location.bindingKey());
            }
        }
        sources.putAll(configuredSources);
        return descriptor.withSourceTexts(sources);
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
        for (int index = 0; index < descriptor.detailRelations().size(); index++) {
            var relation = descriptor.detailRelations().get(index);
            String path = "/detailRelations/" + index, source = key + "/detailRelations/" + segment(relation.code());
            text(path, source, "title", relation.title());
            if (relation.listProjection() != null) {
                boolean previous = configured;
                configured = relation.listProjection().uiConfigId() != null;
                try {
                    for (int fieldIndex = 0; fieldIndex < relation.listProjection().fields().size(); fieldIndex++) {
                        var field = relation.listProjection().fields().get(fieldIndex);
                        text(path + "/listProjection/fields/" + fieldIndex, source + "/listProjection/fields/" + segment(field.fieldName()), "title", field.title());
                    }
                } finally { configured = previous; }
            }
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
            for (int index = 0; index < list.persistentQueryControls().size(); index++) {
                var control = list.persistentQueryControls().get(index);
                text("/page/list/persistentQueryControls/" + index, pageKey + "/list/persistentQueryControls/" + segment(control.id()), "title", control.title());
            }
            for (int index = 0; index < list.querySummaries().size(); index++) {
                var summary = list.querySummaries().get(index);
                String path = "/page/list/querySummaries/" + index, source = pageKey + "/list/querySummaries/" + segment(summary.key());
                text(path, source, "title", summary.title());
                text(path, source, "groupByTitle", summary.groupByTitle());
                text(path, source, "sumFieldTitle", summary.sumFieldTitle());
            }
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
                if (field.option() != null) for (int optionIndex = 0; optionIndex < field.option().inlineItems().size(); optionIndex++) {
                    var option = field.option().inlineItems().get(optionIndex);
                    if (option.titleSource() != null)
                        locations.add(new Location(fieldPath + "/option/inlineItems/" + optionIndex + "/title",
                                option.titleSource().sourceId(), option.title(), false, option.titleSource()));
                }
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
