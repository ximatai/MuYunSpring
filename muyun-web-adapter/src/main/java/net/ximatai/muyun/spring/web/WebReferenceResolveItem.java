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
        Boolean hasChildren,
        String subtitle
) {
    public WebReferenceResolveItem {
        projections = projections == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(projections));
        affectPatch = affectPatch == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(affectPatch));
    }

    public WebReferenceResolveItem(String id, String title, WebReferenceMatchMode matchedBy,
                                   Map<String, Object> projections, Map<String, Object> affectPatch, Boolean hasChildren) {
        this(id, title, matchedBy, projections, affectPatch, hasChildren, null);
    }

    public WebReferenceResolveItem withSubtitle(String subtitle) {
        return new WebReferenceResolveItem(id, title, matchedBy, projections, affectPatch, hasChildren, subtitle);
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
