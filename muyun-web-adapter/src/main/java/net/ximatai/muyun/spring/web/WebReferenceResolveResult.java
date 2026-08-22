package net.ximatai.muyun.spring.web;

import java.util.List;

/** Per-input result returned when translating existing reference values. */
public record WebReferenceResolveResult(
        Object input,
        WebReferenceResolveStatus status,
        WebReferenceMatchMode matchedBy,
        WebReferenceResolveItem item,
        List<WebReferenceResolveItem> candidates
) {
    public WebReferenceResolveResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
