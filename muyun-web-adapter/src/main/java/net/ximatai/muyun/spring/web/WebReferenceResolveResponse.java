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
        long total,
        List<WebTreeNode<WebReferenceResolveItem>> tree
) {
    public WebReferenceResolveResponse {
        options = options == null ? List.of() : List.copyOf(options);
        results = results == null ? List.of() : List.copyOf(results);
        tree = tree == null ? List.of() : List.copyOf(tree);
    }

    /** Compatibility constructor for list and translation callers predating tree reference delivery. */
    public WebReferenceResolveResponse(WebReferenceResolveStatus status,
                                       WebReferenceResolveMode mode,
                                       List<WebReferenceResolveItem> options,
                                       List<WebReferenceResolveResult> results,
                                       int offset,
                                       int limit,
                                       long total) {
        this(status, mode, options, results, offset, limit, total, List.of());
    }
}
