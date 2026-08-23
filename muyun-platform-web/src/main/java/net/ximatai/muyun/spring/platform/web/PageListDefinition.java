package net.ximatai.muyun.spring.platform.web;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The pageable record-list slot of {@link ModulePageTemplate#LIST_DETAIL_CARD}. */
public record PageListDefinition(String searchPlaceholder, ViewDefinition list,
                                 List<PageListRelationExpansionDefinition> relationExpansions) {
    public PageListDefinition {
        searchPlaceholder = searchPlaceholder == null || searchPlaceholder.isBlank()
                ? "搜索名称、编码或 ID" : searchPlaceholder.trim();
        if (list == null || list.viewKind() != ModuleViewKind.LIST) {
            throw new IllegalArgumentException("page list requires a list view");
        }
        relationExpansions = relationExpansions == null ? List.of() : List.copyOf(relationExpansions);
        if (relationExpansions.stream().map(PageListRelationExpansionDefinition::relationCode).distinct().count()
                != relationExpansions.size()) {
            throw new IllegalArgumentException("duplicate list relation expansion");
        }
    }

    /** Source-compatible constructor for lists without a row expansion. */
    public PageListDefinition(String searchPlaceholder, ViewDefinition list) {
        this(searchPlaceholder, list, List.of());
    }

    public static final class Builder {
        private String searchPlaceholder;
        private ViewDefinition list;
        private final List<PageListRelationExpansionDefinition> relationExpansions = new ArrayList<>();

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
        /**
         * Places a declared relation below each expanded list row without duplicating field semantics.
         * Multiple declarations retain their DSL order and are presented as tabs by the standard list surface.
         */
        public Builder expandRelation(String relationCode,
                                      Consumer<RelationExpansionBuilder> customizer) {
            RelationExpansionBuilder builder = new RelationExpansionBuilder(relationCode);
            if (customizer != null) customizer.accept(builder);
            relationExpansions.add(builder.build());
            return this;
        }
        PageListDefinition build() { return new PageListDefinition(searchPlaceholder, list, relationExpansions); }
    }

    public static final class RelationExpansionBuilder {
        private final String relationCode;
        private List<String> fields = List.of();

        private RelationExpansionBuilder(String relationCode) {
            this.relationCode = relationCode;
        }

        public RelationExpansionBuilder columns(String... values) {
            this.fields = values == null ? List.of() : List.of(values);
            return this;
        }

        private PageListRelationExpansionDefinition build() {
            return new PageListRelationExpansionDefinition(relationCode, fields);
        }
    }
}
