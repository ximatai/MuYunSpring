package net.ximatai.muyun.spring.platform.web;

/** Compiled UI facts for one list-query footer summary. */
public record ResolvedPageListQuerySummaryDescriptor(String key, String title,
                                                     PageListQuerySummaryDefinition.Source source,
                                                     String fieldName,
                                                     String contributorKey,
                                                     String groupByField,
                                                     String groupByTitle,
                                                     String sumFieldTitle) {
    /** Source-compatible constructor for descriptors created before built-in SUM existed. */
    public ResolvedPageListQuerySummaryDescriptor(String key, String title,
                                                  PageListQuerySummaryDefinition.Source source,
                                                  String contributorKey) {
        this(key, title, source, null, contributorKey);
    }

    /** Compatibility constructor for descriptors issued before grouped summaries existed. */
    public ResolvedPageListQuerySummaryDescriptor(String key, String title,
                                                  PageListQuerySummaryDefinition.Source source,
                                                  String fieldName, String contributorKey) {
        this(key, title, source, fieldName, contributorKey, null, null, null);
    }

    public static ResolvedPageListQuerySummaryDescriptor from(PageListQuerySummaryDefinition definition) {
        return new ResolvedPageListQuerySummaryDescriptor(definition.key(), definition.title(), definition.source(),
                definition.fieldName(), definition.contributorKey(), definition.groupByField(),
                definition.groupByField(), definition.fieldName());
    }
}
