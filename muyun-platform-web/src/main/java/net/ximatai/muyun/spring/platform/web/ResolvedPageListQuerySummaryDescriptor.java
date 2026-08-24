package net.ximatai.muyun.spring.platform.web;

/** Compiled UI facts for one list-query footer summary. */
public record ResolvedPageListQuerySummaryDescriptor(String key, String title,
                                                     PageListQuerySummaryDefinition.Source source,
                                                     String contributorKey) {
    public static ResolvedPageListQuerySummaryDescriptor from(PageListQuerySummaryDefinition definition) {
        return new ResolvedPageListQuerySummaryDescriptor(definition.key(), definition.title(), definition.source(),
                definition.contributorKey());
    }
}
