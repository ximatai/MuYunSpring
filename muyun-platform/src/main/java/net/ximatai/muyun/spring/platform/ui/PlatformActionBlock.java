package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionRefreshStrategy;

public record PlatformActionBlock(
        String uiConfigId,
        String type,
        String key,
        String actionCode,
        String title,
        String position,
        String targetUiConfigId,
        String submitPath,
        DynamicActionRefreshStrategy refreshStrategy,
        Integer width,
        Integer height,
        LocalEditFormDescriptor localEditForm,
        String importance
) {
    public PlatformActionBlock(String uiConfigId,
                               String type,
                               String key,
                               String actionCode,
                               String title,
                               String position) {
        this(uiConfigId, type, key, actionCode, title, position, null, null, null, null, null, null, null);
    }

    /** Source-compatible constructor for callers created before local edit form contracts were signed. */
    public PlatformActionBlock(String uiConfigId,
                               String type,
                               String key,
                               String actionCode,
                               String title,
                               String position,
                               String targetUiConfigId,
                               String submitPath,
                               DynamicActionRefreshStrategy refreshStrategy,
                               Integer width,
                               Integer height) {
        this(uiConfigId, type, key, actionCode, title, position, targetUiConfigId, submitPath, refreshStrategy,
                width, height, null, null);
    }

    /** Source-compatible constructor for page action contracts with a local edit form. */
    public PlatformActionBlock(String uiConfigId,
                               String type,
                               String key,
                               String actionCode,
                               String title,
                               String position,
                               String targetUiConfigId,
                               String submitPath,
                               DynamicActionRefreshStrategy refreshStrategy,
                               Integer width,
                               Integer height,
                               LocalEditFormDescriptor localEditForm) {
        this(uiConfigId, type, key, actionCode, title, position, targetUiConfigId, submitPath, refreshStrategy,
                width, height, localEditForm, null);
    }

    public PlatformActionBlock {
        uiConfigId = normalize(uiConfigId);
        type = requireText(type, "action block type");
        key = normalize(key);
        actionCode = requireText(actionCode, "action block actionCode");
        title = normalize(title);
        position = normalize(position);
        targetUiConfigId = normalize(targetUiConfigId);
        submitPath = normalize(submitPath);
        refreshStrategy = refreshStrategy == null ? DynamicActionRefreshStrategy.none() : refreshStrategy;
        width = positive(width, "action block width");
        height = positive(height, "action block height");
        importance = importance(importance);
        if (!"localEdit".equals(type) && localEditForm != null) {
            throw new IllegalArgumentException("only local edit action blocks may declare a local edit form");
        }
    }

    private static String requireText(String value, String name) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Integer positive(Integer value, String name) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String importance(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        String upperCase = normalized.toUpperCase(java.util.Locale.ROOT);
        if (!"PRIMARY".equals(upperCase) && !"STANDARD".equals(upperCase) && !"SECONDARY".equals(upperCase)) {
            throw new IllegalArgumentException("action block importance must be PRIMARY, STANDARD or SECONDARY");
        }
        return upperCase;
    }
}
