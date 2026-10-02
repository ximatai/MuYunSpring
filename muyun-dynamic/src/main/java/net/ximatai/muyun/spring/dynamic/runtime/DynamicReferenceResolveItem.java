package net.ximatai.muyun.spring.dynamic.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

public record DynamicReferenceResolveItem(
        String id,
        String title,
        DynamicReferenceMatchMode matchedBy,
        Map<String, Object> projections,
        Map<String, Object> affectPatch,
        String subtitle
) {
    public DynamicReferenceResolveItem(String id, String title, DynamicReferenceMatchMode matchedBy,
                                       Map<String, Object> projections, Map<String, Object> affectPatch) {
        this(id, title, matchedBy, projections, affectPatch, null);
    }

    public DynamicReferenceResolveItem withSubtitle(String subtitle) {
        return new DynamicReferenceResolveItem(id, title, matchedBy, projections, affectPatch, subtitle);
    }
    public DynamicReferenceResolveItem(String id,
                                       String title,
                                       DynamicReferenceMatchMode matchedBy,
                                       Map<String, Object> projections) {
        this(id, title, matchedBy, projections, Map.of());
    }

    public DynamicReferenceResolveItem {
        projections = projections == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(projections));
        affectPatch = affectPatch == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(affectPatch));
    }
}
