package net.ximatai.muyun.spring.platform.ui;

/** Resolves whether a module exposes the requested navigator read shape. */
public interface PageNavigatorSourceCapabilityResolver {
    boolean supports(String moduleAlias, boolean tree);

    /**
     * Whether the source's currently published contract can serve the requested in-place management UI.
     * Implementations must return false when the source action or editor contract cannot be proved.
     */
    default boolean supportsManagement(String moduleAlias, String editorSurface) {
        return false;
    }

    static PageNavigatorSourceCapabilityResolver unavailable() {
        return (moduleAlias, tree) -> false;
    }
}
