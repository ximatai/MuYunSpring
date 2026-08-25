package net.ximatai.muyun.spring.platform.web;

import java.util.function.Consumer;

/** Template-root definition for an optional scope navigator, tree explorer and one record detail surface. */
public record TreeManagementPageDefinition(PageNavigatorDefinition navigator, PageTreeResourceDefinition treeResource,
                                           PageDetailDefinition detail, PageTraitsDefinition traits)
        implements ModulePageDefinition {
    public TreeManagementPageDefinition {
        traits = traits == null ? new PageTraitsDefinition(null) : traits;
        if (traits.values().contains(PageTrait.RESPONSIVE_DETAIL_SURFACE)) {
            throw new IllegalArgumentException("tree management keeps its detail card persistent");
        }
        if (detail == null) throw new IllegalArgumentException("tree management requires a detail slot");
        if (detail.editor() == null && detail.display() == null) {
            throw new IllegalArgumentException("editorless tree management requires a detail display");
        }
    }

    @Override public ModulePageTemplate template() { return ModulePageTemplate.TREE_MANAGEMENT; }
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private PageNavigatorDefinition navigator;
        private PageTreeResourceDefinition treeResource;
        private PageDetailDefinition detail;
        private PageTraitsDefinition traits;

        public Builder navigator(Consumer<PageNavigatorDefinition.Builder> customizer) {
            PageNavigatorDefinition.Builder builder = new PageNavigatorDefinition.Builder();
            if (customizer != null) customizer.accept(builder);
            navigator = builder.build();
            return this;
        }

        public Builder detail(Consumer<PageDetailDefinition.Builder> customizer) {
            PageDetailDefinition.Builder builder = new PageDetailDefinition.Builder();
            if (customizer != null) customizer.accept(builder);
            detail = builder.build();
            return this;
        }

        /**
         * Makes a separately declared action contribution the page's navigator-scoped main tree.
         * Do not use this for the containing module's ordinary tree; bind navigator context to that
         * tree with {@link PageNavigatorDefinition.Builder#bindNavigatorToList(String, String)}.
         */
        public Builder treeResource(String resource, String scopeNavigatorKey, String scopeField,
                                    Consumer<PageTreeResourceDefinition.Builder> customizer) {
            PageTreeResourceDefinition.Builder builder = new PageTreeResourceDefinition.Builder(
                    resource, scopeNavigatorKey, scopeField);
            if (customizer != null) customizer.accept(builder);
            treeResource = builder.build();
            return this;
        }

        public Builder traits(Consumer<PageTraitsDefinition.Builder> customizer) {
            PageTraitsDefinition.Builder builder = PageTraitsDefinition.builder();
            if (customizer != null) customizer.accept(builder);
            traits = builder.build();
            return this;
        }

        public TreeManagementPageDefinition build() {
            return new TreeManagementPageDefinition(navigator, treeResource, detail, traits);
        }
    }
}
