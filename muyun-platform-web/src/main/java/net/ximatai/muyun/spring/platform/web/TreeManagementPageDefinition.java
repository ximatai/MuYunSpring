package net.ximatai.muyun.spring.platform.web;

import java.util.function.Consumer;

/** Template-root definition for a tree explorer and one record detail surface. */
public record TreeManagementPageDefinition(PageDetailDefinition detail, PageTraitsDefinition traits)
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
        private PageDetailDefinition detail;
        private PageTraitsDefinition traits;

        public Builder detail(Consumer<PageDetailDefinition.Builder> customizer) {
            PageDetailDefinition.Builder builder = new PageDetailDefinition.Builder();
            if (customizer != null) customizer.accept(builder);
            detail = builder.build();
            return this;
        }

        public Builder traits(Consumer<PageTraitsDefinition.Builder> customizer) {
            PageTraitsDefinition.Builder builder = PageTraitsDefinition.builder();
            if (customizer != null) customizer.accept(builder);
            traits = builder.build();
            return this;
        }

        public TreeManagementPageDefinition build() { return new TreeManagementPageDefinition(detail, traits); }
    }
}
