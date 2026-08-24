package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.Preconditions;

/** A footer summary declared by a standard pageable list. */
public record PageListQuerySummaryDefinition(String key, String title, Source source, String contributorKey) {
    public enum Source { MATCHED_COUNT, CONTRIBUTOR }

    public PageListQuerySummaryDefinition {
        key = Preconditions.requireText(key, "list query summary key");
        title = Preconditions.requireText(title, "list query summary title");
        if (source == null) throw new IllegalArgumentException("list query summary source must not be null");
        contributorKey = contributorKey == null || contributorKey.isBlank() ? null : contributorKey.trim();
        if (source == Source.CONTRIBUTOR && contributorKey == null) {
            throw new IllegalArgumentException("contributor list query summary requires a contributor key");
        }
        if (source == Source.MATCHED_COUNT && contributorKey != null) {
            throw new IllegalArgumentException("matched-count list query summary must not declare a contributor key");
        }
    }

    public static Builder builder(String key) { return new Builder(key); }

    public static final class Builder {
        private final String key;
        private String title;
        private Source source;
        private String contributorKey;
        private Builder(String key) { this.key = key; }
        public Builder label(String value) { title = value; return this; }
        /** Uses the total of the current effective query, independently of pagination. */
        public Builder matchedCount() { source = Source.MATCHED_COUNT; contributorKey = null; return this; }
        /** Delegates a domain-specific aggregate to a registered, scope-safe contributor. */
        public Builder contributor(String value) { source = Source.CONTRIBUTOR; contributorKey = value; return this; }
        PageListQuerySummaryDefinition build() { return new PageListQuerySummaryDefinition(key, title, source, contributorKey); }
    }
}
