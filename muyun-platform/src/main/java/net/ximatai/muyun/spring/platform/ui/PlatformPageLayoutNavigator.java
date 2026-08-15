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
                        optionalText(level, "searchPlaceholder", config), queryBindings(level.get("queryBindings"), config),
                        childBindings(level.get("childBindings"), config)));
            }
            return new PlatformPageNavigatorLayout(values);
        } catch (IOException exception) {
            throw new IllegalArgumentException("page layout JSON cannot be decoded: " + config.getId(), exception);
        }
    }

    public static List<PlatformPageNavigatorQueryBinding> queryBindings(PlatformUiConfig config) {
        PlatformPageNavigatorLayout navigator = navigator(config);
        return navigator == null ? List.of() : navigator.levels().stream()
                .flatMap(level -> level.queryBindings().stream())
                .toList();
    }

    private static List<PlatformPageNavigatorQueryBinding> queryBindings(JsonNode bindings, PlatformUiConfig config) {
        if (bindings == null || bindings.isNull()) return List.of();
        if (!bindings.isArray()) throw new IllegalArgumentException("navigator query bindings must be an array: " + config.getId());
        List<PlatformPageNavigatorQueryBinding> values = new ArrayList<>();
        for (JsonNode binding : bindings) {
            values.add(new PlatformPageNavigatorQueryBinding(text(binding, "field", config),
                    optionalText(binding, "queryCriteriaKey", config)));
        }
        return values;
    }

    private static List<PlatformPageNavigatorChildBinding> childBindings(JsonNode bindings, PlatformUiConfig config) {
        if (bindings == null || bindings.isNull()) return List.of();
        if (!bindings.isArray()) throw new IllegalArgumentException("navigator child bindings must be an array: " + config.getId());
        List<PlatformPageNavigatorChildBinding> values = new ArrayList<>();
        for (JsonNode binding : bindings) {
            values.add(new PlatformPageNavigatorChildBinding(text(binding, "childLevelKey", config),
                    text(binding, "childQueryCriteriaKey", config)));
        }
        return values;
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
