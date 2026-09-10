package net.ximatai.muyun.spring.platform.web;

import java.util.List;

/** Composer directory for the finite built-in and domain-owned list footer metrics of one module. */
public record PageQuerySummaryCatalog(String moduleAlias, List<Field> fields, List<GroupField> groupFields,
                                      List<Contributor> contributors) {
    public PageQuerySummaryCatalog {
        fields = fields == null ? List.of() : List.copyOf(fields);
        groupFields = groupFields == null ? List.of() : List.copyOf(groupFields);
        contributors = contributors == null ? List.of() : List.copyOf(contributors);
    }

    /** Compatibility constructor for callers built before grouped summaries existed. */
    public PageQuerySummaryCatalog(String moduleAlias, List<Field> fields, List<Contributor> contributors) {
        this(moduleAlias, fields, List.of(), contributors);
    }

    public record Field(String fieldName, String title) {}
    public record GroupField(String fieldName, String title, ListQuerySummaryGroupFieldCatalog.Kind kind) {}
    public record Contributor(String contributorKey, String title) {}
}
