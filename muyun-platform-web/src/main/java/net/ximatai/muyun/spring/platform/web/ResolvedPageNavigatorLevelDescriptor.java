package net.ximatai.muyun.spring.platform.web;

import java.util.List;

/** Resolved form of one navigator level; DSR may omit levels not selectable for the current user. */
public record ResolvedPageNavigatorLevelDescriptor(String key,
                                                   PageNavigatorKind kind,
                                                   String sourceModuleAlias,
                                                   String title,
                                                   String searchPlaceholder,
                                                   String secondaryField,
                                                   ResolvedPageNavigatorManagementDescriptor management,
                                                   PageNavigatorSingleResultPolicy singleResultPolicy,
                                                   PageNavigatorInitialSelectionPolicy initialSelectionPolicy,
                                                   PageNavigatorSourceScope sourceScope) {
    /** Compatibility constructor for resolved descriptors without a secondary identity. */
    public ResolvedPageNavigatorLevelDescriptor(String key,
                                                PageNavigatorKind kind,
                                                String sourceModuleAlias,
                                                String title,
                                                String searchPlaceholder,
                                                ResolvedPageNavigatorManagementDescriptor management,
                                                PageNavigatorSingleResultPolicy singleResultPolicy,
                                                PageNavigatorInitialSelectionPolicy initialSelectionPolicy,
                                                PageNavigatorSourceScope sourceScope) {
        this(key, kind, sourceModuleAlias, title, searchPlaceholder, null, management, singleResultPolicy,
                initialSelectionPolicy, sourceScope);
    }

    public ResolvedPageNavigatorLevelDescriptor {
        singleResultPolicy = singleResultPolicy == null ? PageNavigatorSingleResultPolicy.NONE : singleResultPolicy;
        initialSelectionPolicy = initialSelectionPolicy == null ? PageNavigatorInitialSelectionPolicy.NONE
                : initialSelectionPolicy;
        sourceScope = sourceScope == null ? PageNavigatorSourceScope.NONE : sourceScope;
    }

    static ResolvedPageNavigatorLevelDescriptor from(PageNavigatorLevelDefinition definition) {
        return new ResolvedPageNavigatorLevelDescriptor(definition.key(), definition.kind(), definition.sourceModuleAlias(),
                definition.title(), definition.searchPlaceholder(), definition.secondaryField(),
                ResolvedPageNavigatorManagementDescriptor.from(definition.management()),
                definition.singleResultPolicy(), definition.initialSelectionPolicy(), definition.sourceScope());
    }
}
