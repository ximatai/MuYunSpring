package net.ximatai.muyun.spring.platform.web;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable registry of server-owned page-selection resolvers. */
public final class PageSelectionContextResolverRegistry {
    private final Map<String, PageSelectionContextResolver> resolvers;

    public PageSelectionContextResolverRegistry(Collection<? extends PageSelectionContextResolver> resolvers) {
        Map<String, PageSelectionContextResolver> registered = new LinkedHashMap<>();
        if (resolvers != null) {
            for (PageSelectionContextResolver resolver : resolvers) {
                if (resolver == null) continue;
                String kind = resolver.selectionKind();
                if (kind == null || kind.isBlank()) {
                    throw new IllegalArgumentException("page selection resolver kind must not be blank");
                }
                if (registered.putIfAbsent(kind, resolver) != null) {
                    throw new IllegalArgumentException("duplicate page selection resolver: " + kind);
                }
            }
        }
        this.resolvers = Map.copyOf(registered);
    }

    public ResolvedPageSelectionContext resolve(PageSelectionContextRequest request) {
        if (request == null) throw new IllegalArgumentException("page selection request must not be null");
        PageSelectionContextResolver resolver = resolvers.get(request.selectionKind());
        if (resolver == null) {
            throw new IllegalArgumentException("page selection resolver is not registered: " + request.selectionKind());
        }
        ResolvedPageSelectionContext resolved = resolver.resolve(request);
        if (resolved == null || !request.selectionKind().equals(resolved.selectionKind())
                || !request.selectionKey().equals(resolved.selectionKey())) {
            throw new IllegalStateException("page selection resolver returned a mismatched selection context");
        }
        return resolved;
    }
}
