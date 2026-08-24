package net.ximatai.muyun.spring.platform.web;

import java.util.List;

/** Source-neutral pageable-list slot. */
public record ResolvedPageListDescriptor(String searchPlaceholder, ResolvedViewDescriptor fields,
                                         List<ResolvedPageListRelationExpansionDescriptor> relationExpansions,
                                         List<ResolvedPageListPersistentQueryControlDescriptor> persistentQueryControls) {
    public ResolvedPageListDescriptor {
        relationExpansions = relationExpansions == null ? List.of() : List.copyOf(relationExpansions);
        persistentQueryControls = persistentQueryControls == null ? List.of() : List.copyOf(persistentQueryControls);
    }

    public ResolvedPageListDescriptor(String searchPlaceholder, ResolvedViewDescriptor fields) {
        this(searchPlaceholder, fields, List.of(), List.of());
    }

    public ResolvedPageListDescriptor(String searchPlaceholder, ResolvedViewDescriptor fields,
                                      List<ResolvedPageListRelationExpansionDescriptor> relationExpansions) {
        this(searchPlaceholder, fields, relationExpansions, List.of());
    }
}
