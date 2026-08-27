package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

import java.util.Arrays;
import java.util.List;

/**
 * A single, typed flow of page context from its source to a page consumer.
 * Navigator levels only describe selectable sources; all data propagation lives here.
 */
public record PageContextBindingDefinition(PageContextSource source,
                                           String sourceKey,
                                           PageContextTarget target,
                                           String targetKey,
                                           String targetNavigatorLevelKey,
                                           String targetPickerFieldKey,
                                           NavigatorListQueryMode navigatorListQueryMode) {
    public PageContextBindingDefinition(PageContextSource source, String sourceKey, PageContextTarget target,
                                        String targetKey, String targetNavigatorLevelKey) {
        this(source, sourceKey, target, targetKey, targetNavigatorLevelKey, null, null);
    }

    public PageContextBindingDefinition(PageContextSource source, String sourceKey, PageContextTarget target,
                                        String targetKey, String targetNavigatorLevelKey, String targetPickerFieldKey) {
        this(source, sourceKey, target, targetKey, targetNavigatorLevelKey, targetPickerFieldKey, null);
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
        if (target == PageContextTarget.MUTATION_CONSTRAINT
                && source != PageContextSource.SESSION
                && source != PageContextSource.RESOLVED_SELECTION) {
            throw new IllegalArgumentException("mutation constraints require a server-authoritative SESSION or RESOLVED_SELECTION source");
        }
        if (navigatorListQueryMode != null && (source != PageContextSource.NAVIGATOR
                || target != PageContextTarget.LIST_QUERY)) {
            throw new IllegalArgumentException("navigator list query mode requires a NAVIGATOR LIST_QUERY binding");
        }
        if (source == PageContextSource.NAVIGATOR && target == PageContextTarget.LIST_QUERY
                && navigatorListQueryMode == null) {
            navigatorListQueryMode = NavigatorListQueryMode.REQUIRED_SCOPE;
        }
    }

    public static PageContextBindingDefinition navigator(String sourceLevelKey, PageContextTarget target,
                                                         String targetKey) {
        return new PageContextBindingDefinition(PageContextSource.NAVIGATOR, sourceLevelKey, target, targetKey, null);
    }

    public static PageContextBindingDefinition navigatorList(String sourceLevelKey, String targetKey,
                                                              NavigatorListQueryMode queryMode) {
        return new PageContextBindingDefinition(PageContextSource.NAVIGATOR, sourceLevelKey,
                PageContextTarget.LIST_QUERY, targetKey, null, null, queryMode);
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

    /**
     * Binds a named, server-resolved selection to a page target. The browser may only provide the
     * selection key; the named resolver produces the field value after authorization.
     */
    public static PageContextBindingDefinition resolvedSelection(String selectionKind, PageContextTarget target,
                                                                 String targetKey) {
        return new PageContextBindingDefinition(PageContextSource.RESOLVED_SELECTION,
                selectionKind, target, targetKey, null);
    }

    /** Binds several fields produced by the same trusted selection to one page target. */
    public static List<PageContextBindingDefinition> resolvedSelectionFields(String selectionKind,
                                                                              PageContextTarget target,
                                                                              String... targetKeys) {
        if (targetKeys == null || targetKeys.length == 0) return List.of();
        return Arrays.stream(targetKeys)
                .map(targetKey -> resolvedSelection(selectionKind, target, targetKey))
                .toList();
    }
}
