package net.ximatai.muyun.spring.platform.web;

/** Source-neutral declaration that the client runtime may bind to a registered workbench view. */
public record ResolvedPageDetailWorkspaceViewDescriptor(String type) {
    public ResolvedPageDetailWorkspaceViewDescriptor {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("resolved page detail workspace view type must not be blank");
        }
        type = type.trim();
    }
}
