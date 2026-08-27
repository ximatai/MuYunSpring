package net.ximatai.muyun.spring.platform.web;

import java.util.List;

/** Source-neutral pageable-list slot. */
public record ResolvedPageListDescriptor(String searchPlaceholder, ResolvedViewDescriptor fields,
                                         ResolvedPageTextDescriptor title, ResolvedPageTextDescriptor subtitle,
                                         List<ResolvedPageListRelationExpansionDescriptor> relationExpansions,
                                         List<ResolvedPageListPersistentQueryControlDescriptor> persistentQueryControls,
                                         List<ResolvedPageListQuerySummaryDescriptor> querySummaries) {
    public ResolvedPageListDescriptor {
        relationExpansions = relationExpansions == null ? List.of() : List.copyOf(relationExpansions);
        persistentQueryControls = persistentQueryControls == null ? List.of() : List.copyOf(persistentQueryControls);
        querySummaries = querySummaries == null ? List.of() : List.copyOf(querySummaries);
    }

    public ResolvedPageListDescriptor(String searchPlaceholder, ResolvedViewDescriptor fields) {
        this(searchPlaceholder, fields, null, null, List.of(), List.of(), List.of());
    }

    public ResolvedPageListDescriptor(String searchPlaceholder, ResolvedViewDescriptor fields,
                                      List<ResolvedPageListRelationExpansionDescriptor> relationExpansions) {
        this(searchPlaceholder, fields, null, null, relationExpansions, List.of(), List.of());
    }

    /** Source-compatible constructor for plans compiled before footer summaries were introduced. */
    public ResolvedPageListDescriptor(String searchPlaceholder, ResolvedViewDescriptor fields,
                                      List<ResolvedPageListRelationExpansionDescriptor> relationExpansions,
                                      List<ResolvedPageListPersistentQueryControlDescriptor> persistentQueryControls) {
        this(searchPlaceholder, fields, null, null, relationExpansions, persistentQueryControls, List.of());
    }
}
