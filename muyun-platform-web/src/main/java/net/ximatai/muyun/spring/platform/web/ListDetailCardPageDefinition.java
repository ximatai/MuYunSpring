package net.ximatai.muyun.spring.platform.web;

import java.util.function.Consumer;

/** Template-root definition for an optional scope navigator, pageable list and detail card. */
public record ListDetailCardPageDefinition(PageNavigatorDefinition navigator, PageListDefinition list,
                                           PageDetailDefinition detail, PageTraitsDefinition traits)
        implements ModulePageDefinition {
    public ListDetailCardPageDefinition {
        if (list == null) throw new IllegalArgumentException("list/detail card requires a list slot");
        if (detail == null) throw new IllegalArgumentException("list/detail card requires a detail slot");
        traits = traits == null ? new PageTraitsDefinition(null) : traits;
    }

    @Override public ModulePageTemplate template() { return ModulePageTemplate.LIST_DETAIL_CARD; }
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private PageNavigatorDefinition navigator;
        private PageListDefinition list;
        private PageDetailDefinition detail;
        private PageTraitsDefinition traits;
        public Builder navigator(Consumer<PageNavigatorDefinition.Builder> customizer) {
            PageNavigatorDefinition.Builder builder = new PageNavigatorDefinition.Builder();
            if (customizer != null) customizer.accept(builder);
            navigator = builder.build();
            return this;
        }
        public Builder list(Consumer<PageListDefinition.Builder> customizer) {
            PageListDefinition.Builder builder = new PageListDefinition.Builder();
            if (customizer != null) customizer.accept(builder);
            list = builder.build();
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
        public ListDetailCardPageDefinition build() { return new ListDetailCardPageDefinition(navigator, list, detail, traits); }
    }
}
