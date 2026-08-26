package net.ximatai.muyun.spring.platform.web;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The pageable record-list slot of {@link ModulePageTemplate#LIST_DETAIL_CARD}. */
public record PageListDefinition(String searchPlaceholder, ViewDefinition list,
                                 PageTextDefinition title, PageTextDefinition subtitle,
                                 List<PageListRelationExpansionDefinition> relationExpansions,
                                 List<PageListPersistentQueryControlDefinition> persistentQueryControls,
                                 List<PageListQuerySummaryDefinition> querySummaries) {
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
        persistentQueryControls = persistentQueryControls == null ? List.of() : List.copyOf(persistentQueryControls);
        if (persistentQueryControls.stream().map(PageListPersistentQueryControlDefinition::externalCriteriaKey)
                .distinct().count() != persistentQueryControls.size()) {
            throw new IllegalArgumentException("duplicate persistent query control external criteria key");
        }
        querySummaries = querySummaries == null ? List.of() : List.copyOf(querySummaries);
        if (querySummaries.stream().map(PageListQuerySummaryDefinition::key).distinct().count() != querySummaries.size()) {
            throw new IllegalArgumentException("duplicate list query summary key");
        }
    }

    /** Source-compatible constructor for lists without a row expansion. */
    public PageListDefinition(String searchPlaceholder, ViewDefinition list) {
        this(searchPlaceholder, list, null, null, List.of(), List.of(), List.of());
    }

    /** Source-compatible constructor for lists without persistent query controls. */
    public PageListDefinition(String searchPlaceholder, ViewDefinition list,
                              List<PageListRelationExpansionDefinition> relationExpansions) {
        this(searchPlaceholder, list, null, null, relationExpansions, List.of(), List.of());
    }

    /** Source-compatible constructor for lists with persistent controls but no footer summaries. */
    public PageListDefinition(String searchPlaceholder, ViewDefinition list,
                              List<PageListRelationExpansionDefinition> relationExpansions,
                              List<PageListPersistentQueryControlDefinition> persistentQueryControls) {
        this(searchPlaceholder, list, null, null, relationExpansions, persistentQueryControls, List.of());
    }

    public static final class Builder {
        private String searchPlaceholder;
        private ViewDefinition list;
        private PageTextDefinition title;
        private PageTextDefinition subtitle;
        private final List<PageListRelationExpansionDefinition> relationExpansions = new ArrayList<>();
        private final List<PageListPersistentQueryControlDefinition> persistentQueryControls = new ArrayList<>();
        private final List<PageListQuerySummaryDefinition> querySummaries = new ArrayList<>();

        public Builder searchPlaceholder(String value) { searchPlaceholder = value; return this; }
        /** Declares the descriptor-owned header in the main list content region. */
        public Builder header(Consumer<HeaderBuilder> customizer) {
            HeaderBuilder builder = new HeaderBuilder();
            if (customizer != null) customizer.accept(builder);
            title = builder.title;
            subtitle = builder.subtitle;
            return this;
        }
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
        /** Declares the persistent query area after quick search and before advanced filtering. */
        public Builder persistentQueries(Consumer<PersistentQueriesBuilder> customizer) {
            PersistentQueriesBuilder builder = new PersistentQueriesBuilder();
            if (customizer != null) customizer.accept(builder);
            persistentQueryControls.addAll(builder.controls);
            return this;
        }
        /** Declares footer facts calculated from the current complete query result, not its page. */
        public Builder querySummaries(Consumer<QuerySummariesBuilder> customizer) {
            QuerySummariesBuilder builder = new QuerySummariesBuilder();
            if (customizer != null) customizer.accept(builder);
            querySummaries.addAll(builder.items);
            return this;
        }
        PageListDefinition build() {
            return new PageListDefinition(searchPlaceholder, list, title, subtitle,
                    relationExpansions, persistentQueryControls, querySummaries);
        }
    }

    /** Copy owned by the list's main-content header, never by application chrome or navigation. */
    public static final class HeaderBuilder {
        private PageTextDefinition title;
        private PageTextDefinition subtitle;
        public HeaderBuilder title(String value) { title = PageTextDefinition.text(value); return this; }
        public HeaderBuilder titleExpression(String value) { title = PageTextDefinition.expression(value); return this; }
        public HeaderBuilder subtitle(String value) { subtitle = PageTextDefinition.text(value); return this; }
        public HeaderBuilder subtitleExpression(String value) { subtitle = PageTextDefinition.expression(value); return this; }
    }

    public static final class QuerySummariesBuilder {
        private final List<PageListQuerySummaryDefinition> items = new ArrayList<>();
        public QuerySummariesBuilder item(String key, Consumer<PageListQuerySummaryDefinition.Builder> customizer) {
            PageListQuerySummaryDefinition.Builder builder = PageListQuerySummaryDefinition.builder(key);
            if (customizer != null) customizer.accept(builder);
            items.add(builder.build());
            return this;
        }
    }

    /** The standard persistent query area; every registered control applies its value immediately. */
    public static final class PersistentQueriesBuilder {
        private final List<PageListPersistentQueryControlDefinition> controls = new ArrayList<>();

        public PersistentQueriesBuilder control(String externalCriteriaKey,
                                                Consumer<PageListPersistentQueryControlDefinition.Builder> customizer) {
            PageListPersistentQueryControlDefinition.Builder builder =
                    PageListPersistentQueryControlDefinition.builder(externalCriteriaKey);
            if (customizer != null) customizer.accept(builder);
            controls.add(builder.build());
            return this;
        }
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
