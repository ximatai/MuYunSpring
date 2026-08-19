package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

import java.util.Arrays;
import java.util.EnumSet;


/** One selectable source within a page navigator. */
public record PageNavigatorLevelDefinition(String key,
                                           PageNavigatorKind kind,
                                           String sourceModuleAlias,
                                           String title,
                                           String searchPlaceholder,
                                           PageNavigatorManagementDefinition management,
                                           PageNavigatorSingleResultPolicy singleResultPolicy,
                                           PageNavigatorInitialSelectionPolicy initialSelectionPolicy,
                                           PageNavigatorSourceScope sourceScope) {
    public PageNavigatorLevelDefinition {
        key = PlatformNameRules.requireFieldName(key, "navigator level key");
        if (kind == null) throw new IllegalArgumentException("navigator level kind must not be null");
        sourceModuleAlias = PlatformNameRules.requireModuleAlias(sourceModuleAlias);
        title = title == null || title.isBlank() ? "范围" : title.trim();
        searchPlaceholder = searchPlaceholder == null || searchPlaceholder.isBlank()
                ? "搜索" + title : searchPlaceholder.trim();
        singleResultPolicy = singleResultPolicy == null ? PageNavigatorSingleResultPolicy.NONE : singleResultPolicy;
        initialSelectionPolicy = initialSelectionPolicy == null ? PageNavigatorInitialSelectionPolicy.NONE
                : initialSelectionPolicy;
        sourceScope = sourceScope == null ? PageNavigatorSourceScope.NONE : sourceScope;
    }

    public static final class Builder {
        private final String key;
        private PageNavigatorKind kind;
        private String sourceModuleAlias;
        private String title;
        private String searchPlaceholder;
        private PageNavigatorManagementDefinition management;
        private PageNavigatorSingleResultPolicy singleResultPolicy = PageNavigatorSingleResultPolicy.NONE;
        private PageNavigatorInitialSelectionPolicy initialSelectionPolicy = PageNavigatorInitialSelectionPolicy.NONE;
        private PageNavigatorSourceScope sourceScope = PageNavigatorSourceScope.NONE;

        Builder(String key) { this.key = key; }

        public Builder tree(String sourceModuleAlias, String title, String searchPlaceholder) {
            return source(PageNavigatorKind.TREE, sourceModuleAlias, title, searchPlaceholder);
        }

        public Builder microList(String sourceModuleAlias, String title, String searchPlaceholder) {
            return source(PageNavigatorKind.MICRO_LIST, sourceModuleAlias, title, searchPlaceholder);
        }

        /**
         * Enables standard create, edit and delete affordances for this source.
         * The source module owns authorization; the optional surface chooses its
         * form schema without duplicating fields in the containing page.
         */
        public Builder manageable(String editorSurface) {
            management = new PageNavigatorManagementDefinition(editorSurface);
            return this;
        }

        /**
         * Enables only the listed standard source-management affordances.
         * Source action authorization and record availability are still enforced.
         */
        public Builder manageable(PageNavigatorManagementAction... actions) {
            return manageable(null, actions);
        }

        /** Enables only the listed standard affordances with a named source editor. */
        public Builder manageable(String editorSurface, PageNavigatorManagementAction... actions) {
            EnumSet<PageNavigatorManagementAction> configured = actions == null || actions.length == 0
                    ? EnumSet.noneOf(PageNavigatorManagementAction.class)
                    : EnumSet.copyOf(Arrays.asList(actions));
            management = new PageNavigatorManagementDefinition(editorSurface, configured);
            return this;
        }

        /** Enables in-place management with the source module's default editor. */
        public Builder manageable() {
            return manageable((String) null);
        }

        /** Selects the sole accessible source record and optionally collapses its panel. */
        public Builder singleResultPolicy(PageNavigatorSingleResultPolicy value) {
            singleResultPolicy = value == null ? PageNavigatorSingleResultPolicy.NONE : value;
            return this;
        }

        /** Opt-in initial selection; unspecified navigators preserve an empty selection. */
        public Builder initialSelectionPolicy(PageNavigatorInitialSelectionPolicy value) {
            initialSelectionPolicy = value == null ? PageNavigatorInitialSelectionPolicy.NONE : value;
            return this;
        }

        /** Declares that the source is constrained by authenticated tenant context. */
        public Builder sourceScope(PageNavigatorSourceScope value) {
            sourceScope = value == null ? PageNavigatorSourceScope.NONE : value;
            return this;
        }

        private Builder source(PageNavigatorKind kind, String sourceModuleAlias, String title,
                               String searchPlaceholder) {
            if (this.kind != null) throw new IllegalArgumentException("navigator level source is already declared: " + key);
            this.kind = kind;
            this.sourceModuleAlias = sourceModuleAlias;
            this.title = title;
            this.searchPlaceholder = searchPlaceholder;
            return this;
        }

        PageNavigatorLevelDefinition build() {
            return new PageNavigatorLevelDefinition(key, kind, sourceModuleAlias, title, searchPlaceholder,
                    management, singleResultPolicy, initialSelectionPolicy, sourceScope);
        }
    }
}
