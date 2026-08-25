package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/** Serialized page-layout fact for one optional navigator column. */
public record PlatformPageNavigatorLevel(String key,
                                         String kind,
                                         String sourceModuleAlias,
                                         String title,
                                         String searchPlaceholder,
                                         String secondaryField,
                                         PlatformPageNavigatorManagement management,
                                         String singleResultPolicy,
                                         String initialSelectionPolicy,
                                         String sourceScope) {
    private static final java.util.Set<String> SINGLE_RESULT_POLICIES = java.util.Set.of(
            "NONE", "AUTO_SELECT", "AUTO_SELECT_AND_HIDE");
    private static final java.util.Set<String> INITIAL_SELECTION_POLICIES = java.util.Set.of(
            "NONE", "FIRST_RECORD");
    private static final java.util.Set<String> SOURCE_SCOPES = java.util.Set.of(
            "NONE", "CURRENT_TENANT");

    public PlatformPageNavigatorLevel {
        key = PlatformNameRules.requireFieldName(key, "navigator level key");
        if (!"TREE".equals(kind) && !"MICRO_LIST".equals(kind)) {
            throw new IllegalArgumentException("navigator level kind must be TREE or MICRO_LIST: " + kind);
        }
        sourceModuleAlias = PlatformNameRules.requireModuleAlias(sourceModuleAlias);
        title = title == null || title.isBlank() ? null : title.trim();
        searchPlaceholder = searchPlaceholder == null || searchPlaceholder.isBlank() ? null : searchPlaceholder.trim();
        secondaryField = secondaryField == null || secondaryField.isBlank()
                ? null : PlatformNameRules.requireFieldName(secondaryField, "navigator secondary field");
        singleResultPolicy = singleResultPolicy == null || singleResultPolicy.isBlank()
                ? "NONE" : singleResultPolicy.trim();
        initialSelectionPolicy = initialSelectionPolicy == null || initialSelectionPolicy.isBlank()
                ? "NONE" : initialSelectionPolicy.trim();
        sourceScope = sourceScope == null || sourceScope.isBlank() ? "NONE" : sourceScope.trim();
        requireSupported(singleResultPolicy, SINGLE_RESULT_POLICIES, "singleResultPolicy");
        requireSupported(initialSelectionPolicy, INITIAL_SELECTION_POLICIES, "initialSelectionPolicy");
        requireSupported(sourceScope, SOURCE_SCOPES, "sourceScope");
    }

    public PlatformPageNavigatorLevel(String key, String kind, String sourceModuleAlias, String title,
                                      String searchPlaceholder, PlatformPageNavigatorManagement management,
                                      String singleResultPolicy, String initialSelectionPolicy, String sourceScope) {
        this(key, kind, sourceModuleAlias, title, searchPlaceholder, null, management, singleResultPolicy,
                initialSelectionPolicy, sourceScope);
    }

    private static void requireSupported(String value, java.util.Set<String> supported, String name) {
        if (!supported.contains(value)) {
            throw new IllegalArgumentException("navigator level " + name + " is unsupported: " + value);
        }
    }
}
