package net.ximatai.muyun.spring.platform.web;

import java.util.function.Consumer;

/** The pageable record-list slot of {@link ModulePageTemplate#LIST_DETAIL_CARD}. */
public record PageListDefinition(String searchPlaceholder, ViewDefinition list) {
    public PageListDefinition {
        searchPlaceholder = searchPlaceholder == null || searchPlaceholder.isBlank()
                ? "搜索名称、编码或 ID" : searchPlaceholder.trim();
        if (list == null || list.viewKind() != ModuleViewKind.LIST) {
            throw new IllegalArgumentException("page list requires a list view");
        }
    }

    public static final class Builder {
        private String searchPlaceholder;
        private ViewDefinition list;

        public Builder searchPlaceholder(String value) { searchPlaceholder = value; return this; }
        public Builder fields(Consumer<ViewDefinition.Builder> customizer) {
            // The list slot is the module's standard query projection.  Keep its
            // stable view code so the existing query/action protocol need not
            // invent a page-local identifier.
            ViewDefinition.Builder builder = ViewDefinition.list(ModuleUiViewCodes.DEFAULT_LIST);
            if (customizer != null) customizer.accept(builder);
            list = builder.build();
            return this;
        }
        PageListDefinition build() { return new PageListDefinition(searchPlaceholder, list); }
    }
}
