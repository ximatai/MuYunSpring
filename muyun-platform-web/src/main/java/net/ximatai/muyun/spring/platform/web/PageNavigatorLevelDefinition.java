package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

import java.util.ArrayList;
import java.util.List;

/** One selectable source within a page navigator. */
public record PageNavigatorLevelDefinition(String key,
                                           PageNavigatorKind kind,
                                           String sourceModuleAlias,
                                           String title,
                                           String searchPlaceholder,
                                           List<PageNavigatorQueryBindingDefinition> queryBindings,
                                           List<PageNavigatorChildBindingDefinition> childBindings,
                                           PageNavigatorManagementDefinition management) {
    public PageNavigatorLevelDefinition {
        key = PlatformNameRules.requireFieldName(key, "navigator level key");
        if (kind == null) throw new IllegalArgumentException("navigator level kind must not be null");
        sourceModuleAlias = PlatformNameRules.requireModuleAlias(sourceModuleAlias);
        title = title == null || title.isBlank() ? "范围" : title.trim();
        searchPlaceholder = searchPlaceholder == null || searchPlaceholder.isBlank()
                ? "搜索" + title : searchPlaceholder.trim();
        queryBindings = queryBindings == null ? List.of() : List.copyOf(queryBindings);
        childBindings = childBindings == null ? List.of() : List.copyOf(childBindings);
    }

    public static final class Builder {
        private final String key;
        private PageNavigatorKind kind;
        private String sourceModuleAlias;
        private String title;
        private String searchPlaceholder;
        private final List<PageNavigatorQueryBindingDefinition> queryBindings = new ArrayList<>();
        private final List<PageNavigatorChildBindingDefinition> childBindings = new ArrayList<>();
        private PageNavigatorManagementDefinition management;

        Builder(String key) { this.key = key; }

        public Builder tree(String sourceModuleAlias, String title, String searchPlaceholder) {
            return source(PageNavigatorKind.TREE, sourceModuleAlias, title, searchPlaceholder);
        }

        public Builder microList(String sourceModuleAlias, String title, String searchPlaceholder) {
            return source(PageNavigatorKind.MICRO_LIST, sourceModuleAlias, title, searchPlaceholder);
        }

        public Builder bindQuery(String field) { return bindQuery(field, field); }

        /** Binds the selected record ID to the page list's standard external query criterion. */
        public Builder bindQuery(String field, String queryCriteriaKey) {
            queryBindings.add(new PageNavigatorQueryBindingDefinition(field, queryCriteriaKey));
            return this;
        }

        /** Sends the selected record ID to a later navigator level's standard external criterion. */
        public Builder bindChild(String childLevelKey, String childQueryCriteriaKey) {
            childBindings.add(new PageNavigatorChildBindingDefinition(childLevelKey, childQueryCriteriaKey));
            return this;
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

        /** Enables in-place management with the source module's default editor. */
        public Builder manageable() {
            return manageable(null);
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
                    queryBindings, childBindings, management);
        }
    }
}
