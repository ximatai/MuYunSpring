package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/**
 * Serialized page-context data flow.  A navigator is merely one possible
 * source; the target tells consumers which page capability receives it.
 */
public record PlatformPageContextBinding(String source,
                                         String sourceKey,
                                         String target,
                                         String targetKey,
                                         String targetNavigatorLevelKey,
                                         String targetPickerFieldKey) {
    public PlatformPageContextBinding(String source, String sourceKey, String target, String targetKey,
                                      String targetNavigatorLevelKey) {
        this(source, sourceKey, target, targetKey, targetNavigatorLevelKey, null);
    }
    public PlatformPageContextBinding {
        if (!"SESSION".equals(source) && !"NAVIGATOR".equals(source)) {
            throw new IllegalArgumentException("page context binding source is invalid: " + source);
        }
        sourceKey = PlatformNameRules.requireFieldName(sourceKey, "page context binding source key");
        if (!"LIST_QUERY".equals(target) && !"NAVIGATOR_QUERY".equals(target)
                && !"FORM_DEFAULT".equals(target) && !"PICKER_QUERY".equals(target)) {
            throw new IllegalArgumentException("page context binding target is invalid: " + target);
        }
        targetKey = PlatformNameRules.requireFieldName(targetKey, "page context binding target key");
        targetNavigatorLevelKey = targetNavigatorLevelKey == null || targetNavigatorLevelKey.isBlank()
                ? null : PlatformNameRules.requireFieldName(targetNavigatorLevelKey,
                "page context binding target navigator level key");
        if ("NAVIGATOR_QUERY".equals(target) && targetNavigatorLevelKey == null) {
            throw new IllegalArgumentException("navigator-query context binding requires a target navigator level");
        }
        if (!"NAVIGATOR_QUERY".equals(target) && targetNavigatorLevelKey != null) {
            throw new IllegalArgumentException("only navigator-query context bindings can target a navigator level");
        }
        targetPickerFieldKey = targetPickerFieldKey == null || targetPickerFieldKey.isBlank()
                ? null : PlatformNameRules.requireFieldName(targetPickerFieldKey,
                "page context binding target picker field key");
        if ("PICKER_QUERY".equals(target) && targetPickerFieldKey == null) {
            throw new IllegalArgumentException("picker-query context binding requires a target picker field");
        }
        if (!"PICKER_QUERY".equals(target) && targetPickerFieldKey != null) {
            throw new IllegalArgumentException("only picker-query context bindings can target a picker field");
        }
    }
}
