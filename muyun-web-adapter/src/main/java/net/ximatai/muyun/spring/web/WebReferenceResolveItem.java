package net.ximatai.muyun.spring.web;

import java.util.LinkedHashMap;
import java.util.Map;

/** A selectable reference candidate together with its optional field effects. */
public record WebReferenceResolveItem(
        String id,
        String title,
        WebReferenceMatchMode matchedBy,
        Map<String, Object> projections,
        Map<String, Object> affectPatch
) {
    public WebReferenceResolveItem {
        projections = projections == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(projections));
        affectPatch = affectPatch == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(affectPatch));
    }
}
