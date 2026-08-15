package net.ximatai.muyun.spring.platform.web;

import java.util.function.Consumer;

/**
 * Template-root definition for a left record explorer and right detail card management page.
 */
public record FlatManagementPageDefinition(PageNavigatorDefinition navigator, PageExplorerDefinition explorer, PageDetailDefinition detail,
                                           PageTraitsDefinition traits) implements ModulePageDefinition {
    public FlatManagementPageDefinition {
        if (explorer == null) throw new IllegalArgumentException("flat management requires an explorer slot");
        if (detail == null) throw new IllegalArgumentException("flat management requires a detail slot");
        traits = traits == null ? new PageTraitsDefinition(null) : traits;
    }

    @Override public ModulePageTemplate template() { return ModulePageTemplate.FLAT_MANAGEMENT; }
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private PageExplorerDefinition explorer;
        private PageNavigatorDefinition navigator;
        private PageDetailDefinition detail;
        private PageTraitsDefinition traits;
        public Builder explorer(Consumer<PageExplorerDefinition.Builder> customizer) {
            PageExplorerDefinition.Builder builder = PageExplorerDefinition.builder();
            if (customizer != null) customizer.accept(builder);
            explorer = builder.build();
            return this;
        }
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
        public Builder traits(Consumer<PageTraitsDefinition.Builder> customizer) {
            PageTraitsDefinition.Builder builder = PageTraitsDefinition.builder();
            if (customizer != null) customizer.accept(builder);
            traits = builder.build();
            return this;
        }
        public FlatManagementPageDefinition build() { return new FlatManagementPageDefinition(navigator, explorer, detail, traits); }
    }
}
