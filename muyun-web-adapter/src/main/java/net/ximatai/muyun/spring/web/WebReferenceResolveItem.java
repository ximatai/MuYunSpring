package net.ximatai.muyun.spring.web;

import java.util.LinkedHashMap;
import java.util.Map;

/** A selectable reference candidate together with its optional field effects. */
public record WebReferenceResolveItem(
        String id,
        String title,
        WebReferenceMatchMode matchedBy,
        Map<String, Object> projections,
        Map<String, Object> affectPatch,
        /**
         * Whether this candidate has at least one visible child in a lazy tree response.
         * It is intentionally absent for ordinary query and translation results.
         */
        Boolean hasChildren
) {
    public WebReferenceResolveItem {
        projections = projections == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(projections));
        affectPatch = affectPatch == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(affectPatch));
    }

    /** Compatibility constructor for callers that do not deliver lazy-tree structure. */
    public WebReferenceResolveItem(String id,
                                   String title,
                                   WebReferenceMatchMode matchedBy,
                                   Map<String, Object> projections,
                                   Map<String, Object> affectPatch) {
        this(id, title, matchedBy, projections, affectPatch, null);
    }
}
