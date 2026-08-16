package net.ximatai.muyun.spring.platform.web;

import java.util.List;

/** Resolved form of one navigator level; DSR may omit levels not selectable for the current user. */
public record ResolvedPageNavigatorLevelDescriptor(String key,
                                                   PageNavigatorKind kind,
                                                   String sourceModuleAlias,
                                                   String title,
                                                   String searchPlaceholder,
                                                   ResolvedPageNavigatorManagementDescriptor management,
                                                   PageNavigatorSingleResultPolicy singleResultPolicy,
                                                   PageNavigatorSourceScope sourceScope) {
    public ResolvedPageNavigatorLevelDescriptor {
        singleResultPolicy = singleResultPolicy == null ? PageNavigatorSingleResultPolicy.NONE : singleResultPolicy;
        sourceScope = sourceScope == null ? PageNavigatorSourceScope.NONE : sourceScope;
    }

    static ResolvedPageNavigatorLevelDescriptor from(PageNavigatorLevelDefinition definition) {
        return new ResolvedPageNavigatorLevelDescriptor(definition.key(), definition.kind(), definition.sourceModuleAlias(),
                definition.title(), definition.searchPlaceholder(), ResolvedPageNavigatorManagementDescriptor.from(definition.management()),
                definition.singleResultPolicy(), definition.sourceScope());
    }
}
