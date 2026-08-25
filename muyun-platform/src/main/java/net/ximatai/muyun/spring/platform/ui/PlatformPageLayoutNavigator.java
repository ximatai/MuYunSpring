package net.ximatai.muyun.spring.platform.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the navigator portion of dynamic page layout JSON once for every consumer.
 * It deliberately owns no rendering or query execution policy.
 */
public final class PlatformPageLayoutNavigator {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private PlatformPageLayoutNavigator() {
    }

    public static PlatformPageNavigatorLayout navigator(PlatformUiConfig config) {
        if (config == null || config.getLayoutJson() == null || config.getLayoutJson().isBlank()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(config.getLayoutJson());
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("page layout JSON root must be an object: " + config.getId());
            }
            JsonNode navigator = root.get("navigator");
            if (navigator == null || navigator.isNull()) return null;
            if (!navigator.isObject()) {
                throw new IllegalArgumentException("page navigator must be an object: " + config.getId());
            }
            JsonNode levels = navigator.get("levels");
            if (levels == null || !levels.isArray()) {
                throw new IllegalArgumentException("page navigator levels must be an array: " + config.getId());
            }
            List<PlatformPageNavigatorLevel> values = new ArrayList<>();
            for (JsonNode level : levels) {
                values.add(new PlatformPageNavigatorLevel(text(level, "key", config), text(level, "kind", config),
                        text(level, "sourceModuleAlias", config), optionalText(level, "title", config),
                        optionalText(level, "searchPlaceholder", config), management(level.get("management"), config),
                        optionalText(level, "singleResultPolicy", config),
                        optionalText(level, "initialSelectionPolicy", config), optionalText(level, "sourceScope", config)));
            }
            return new PlatformPageNavigatorLayout(values, contextBindings(navigator.get("contextBindings"), config));
        } catch (IOException exception) {
            throw new IllegalArgumentException("page layout JSON cannot be decoded: " + config.getId(), exception);
        }
    }

    public static List<PlatformPageContextBinding> contextBindings(PlatformUiConfig config) {
        PlatformPageNavigatorLayout navigator = navigator(config);
        return navigator == null ? List.of() : navigator.contextBindings();
    }

    private static List<PlatformPageContextBinding> contextBindings(JsonNode bindings, PlatformUiConfig config) {
        if (bindings == null || bindings.isNull()) return List.of();
        if (!bindings.isArray()) throw new IllegalArgumentException("page context bindings must be an array: " + config.getId());
        List<PlatformPageContextBinding> values = new ArrayList<>();
        for (JsonNode binding : bindings) {
            values.add(new PlatformPageContextBinding(text(binding, "source", config), text(binding, "sourceKey", config),
                    text(binding, "target", config), text(binding, "targetKey", config),
                    optionalText(binding, "targetNavigatorLevelKey", config),
                    optionalText(binding, "targetPickerFieldKey", config),
                    optionalText(binding, "navigatorListQueryMode", config)));
        }
        return values;
    }

    private static PlatformPageNavigatorManagement management(JsonNode node, PlatformUiConfig config) {
        if (node == null || node.isNull()) return null;
        if (!node.isObject()) throw new IllegalArgumentException("navigator management must be an object: " + config.getId());
        // `actions` was a short-lived presentation allow-list. Management is
        // now binary; keeping the object makes existing published layouts
        // compatible while normalizing them to complete standard management.
        return new PlatformPageNavigatorManagement(optionalText(node, "editorSurface", config));
    }

    private static String text(JsonNode node, String field, PlatformUiConfig config) {
        String value = optionalText(node, field, config);
        if (value == null) throw new IllegalArgumentException("navigator " + field + " is required: " + config.getId());
        return value;
    }

    private static String optionalText(JsonNode node, String field, PlatformUiConfig config) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("navigator " + field + " must be a string: " + config.getId());
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }
}
