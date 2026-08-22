package net.ximatai.muyun.spring.web;

import java.util.List;

/** Source-neutral result of resolving a field reference. */
public record WebReferenceResolveResponse(
        WebReferenceResolveStatus status,
        WebReferenceResolveMode mode,
        List<WebReferenceResolveItem> options,
        List<WebReferenceResolveResult> results,
        int offset,
        int limit,
        long total
) {
    public WebReferenceResolveResponse {
        options = options == null ? List.of() : List.copyOf(options);
        results = results == null ? List.of() : List.copyOf(results);
    }
}
