package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/**
 * A single, typed flow of page context from its source to a page consumer.
 * Navigator levels only describe selectable sources; all data propagation lives here.
 */
public record PageContextBindingDefinition(PageContextSource source,
                                           String sourceKey,
                                           PageContextTarget target,
                                           String targetKey,
                                           String targetNavigatorLevelKey,
                                           String targetPickerFieldKey) {
    public PageContextBindingDefinition(PageContextSource source, String sourceKey, PageContextTarget target,
                                        String targetKey, String targetNavigatorLevelKey) {
        this(source, sourceKey, target, targetKey, targetNavigatorLevelKey, null);
    }

    public PageContextBindingDefinition {
        if (source == null) throw new IllegalArgumentException("page context source must not be null");
        sourceKey = PlatformNameRules.requireFieldName(sourceKey, "page context source key");
        if (target == null) throw new IllegalArgumentException("page context target must not be null");
        targetKey = PlatformNameRules.requireFieldName(targetKey, "page context target key");
        targetNavigatorLevelKey = targetNavigatorLevelKey == null || targetNavigatorLevelKey.isBlank()
                ? null : PlatformNameRules.requireFieldName(targetNavigatorLevelKey, "page context target navigator key");
        if (target == PageContextTarget.NAVIGATOR_QUERY && targetNavigatorLevelKey == null) {
            throw new IllegalArgumentException("navigator-query context binding requires target navigator level");
        }
        if (target != PageContextTarget.NAVIGATOR_QUERY && targetNavigatorLevelKey != null) {
            throw new IllegalArgumentException("only navigator-query context binding may target a navigator level");
        }
        targetPickerFieldKey = targetPickerFieldKey == null || targetPickerFieldKey.isBlank()
                ? null : PlatformNameRules.requireFieldName(targetPickerFieldKey, "page context target picker field key");
        if (target == PageContextTarget.PICKER_QUERY && targetPickerFieldKey == null) {
            throw new IllegalArgumentException("picker-query context binding requires a target picker field");
        }
        if (target != PageContextTarget.PICKER_QUERY && targetPickerFieldKey != null) {
            throw new IllegalArgumentException("only picker-query context binding may target a picker field");
        }
        if (target == PageContextTarget.MUTATION_CONSTRAINT && source != PageContextSource.SESSION) {
            throw new IllegalArgumentException("mutation constraints require a server-authoritative SESSION source");
        }
    }

    public static PageContextBindingDefinition navigator(String sourceLevelKey, PageContextTarget target,
                                                         String targetKey) {
        return new PageContextBindingDefinition(PageContextSource.NAVIGATOR, sourceLevelKey, target, targetKey, null);
    }

    public static PageContextBindingDefinition navigatorToNavigator(String sourceLevelKey, String targetLevelKey,
                                                                      String targetKey) {
        return new PageContextBindingDefinition(PageContextSource.NAVIGATOR, sourceLevelKey,
                PageContextTarget.NAVIGATOR_QUERY, targetKey, targetLevelKey);
    }

    public static PageContextBindingDefinition navigatorToPickerQuery(String sourceLevelKey, String pickerField,
                                                                       String queryField) {
        return new PageContextBindingDefinition(PageContextSource.NAVIGATOR, sourceLevelKey,
                PageContextTarget.PICKER_QUERY, queryField, null, pickerField);
    }

    public static PageContextBindingDefinition session(String sourceKey, PageContextTarget target, String targetKey) {
        return new PageContextBindingDefinition(PageContextSource.SESSION, sourceKey, target, targetKey, null);
    }
}
