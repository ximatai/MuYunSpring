package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.Preconditions;

/** A footer summary declared by a standard pageable list. */
public record PageListQuerySummaryDefinition(String key, String title, Source source, String fieldName,
                                             String contributorKey, String groupByField) {
    public enum Source { MATCHED_COUNT, SUM, CONTRIBUTOR, GROUPED }

    /** Compatibility constructor for declarations created before grouped summaries existed. */
    public PageListQuerySummaryDefinition(String key, String title, Source source, String fieldName,
                                          String contributorKey) {
        this(key, title, source, fieldName, contributorKey, null);
    }

    public PageListQuerySummaryDefinition {
        key = Preconditions.requireText(key, "list query summary key");
        title = Preconditions.requireText(title, "list query summary title");
        if (source == null) throw new IllegalArgumentException("list query summary source must not be null");
        fieldName = fieldName == null || fieldName.isBlank() ? null : fieldName.trim();
        contributorKey = contributorKey == null || contributorKey.isBlank() ? null : contributorKey.trim();
        groupByField = groupByField == null || groupByField.isBlank() ? null : groupByField.trim();
        if (source == Source.SUM && fieldName == null) {
            throw new IllegalArgumentException("sum list query summary requires a field name");
        }
        if (source == Source.CONTRIBUTOR && contributorKey == null) {
            throw new IllegalArgumentException("contributor list query summary requires a contributor key");
        }
        if (source == Source.GROUPED && groupByField == null) {
            throw new IllegalArgumentException("grouped list query summary requires a group field");
        }
        if (source != Source.SUM && source != Source.GROUPED && fieldName != null) {
            throw new IllegalArgumentException("only sum or grouped list query summary may declare a field name");
        }
        if (source != Source.CONTRIBUTOR && contributorKey != null) {
            throw new IllegalArgumentException("only contributor list query summary may declare a contributor key");
        }
        if (source != Source.GROUPED && groupByField != null) {
            throw new IllegalArgumentException("only grouped list query summary may declare a group field");
        }
    }

    public static Builder builder(String key) { return new Builder(key); }

    public static final class Builder {
        private final String key;
        private String title;
        private Source source;
        private String fieldName;
        private String contributorKey;
        private String groupByField;
        private Builder(String key) { this.key = key; }
        public Builder label(String value) { title = value; return this; }
        /** Uses the total of the current effective query, independently of pagination. */
        public Builder matchedCount() { source = Source.MATCHED_COUNT; fieldName = null; contributorKey = null; groupByField = null; return this; }
        /** Sums one declared physical numeric field over the current effective query. */
        public Builder sum(String value) { source = Source.SUM; fieldName = value; contributorKey = null; groupByField = null; return this; }
        /** Delegates a domain-specific aggregate to a registered, scope-safe contributor. */
        public Builder contributor(String value) { source = Source.CONTRIBUTOR; fieldName = null; contributorKey = value; groupByField = null; return this; }
        /** Counts every current-query group; {@link #groupedSum(String)} optionally adds one safe numeric aggregate. */
        public Builder grouped(String value) { source = Source.GROUPED; groupByField = value; fieldName = null; contributorKey = null; return this; }
        /** Adds the one allowed SUM column to a grouped declaration. */
        public Builder groupedSum(String value) {
            if (source != Source.GROUPED) throw new IllegalStateException("grouped sum requires grouped(field) first");
            fieldName = value;
            return this;
        }
        PageListQuerySummaryDefinition build() { return new PageListQuerySummaryDefinition(key, title, source, fieldName, contributorKey, groupByField); }
    }
}
