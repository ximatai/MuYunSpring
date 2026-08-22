package net.ximatai.muyun.spring.platform.web;

import java.util.List;

/** Source-neutral pageable-list slot. */
public record ResolvedPageListDescriptor(String searchPlaceholder, ResolvedViewDescriptor fields,
                                         List<ResolvedPageListRelationExpansionDescriptor> relationExpansions) {
    public ResolvedPageListDescriptor {
        relationExpansions = relationExpansions == null ? List.of() : List.copyOf(relationExpansions);
    }

    public ResolvedPageListDescriptor(String searchPlaceholder, ResolvedViewDescriptor fields) {
        this(searchPlaceholder, fields, List.of());
    }
}
