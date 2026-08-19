package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

import java.util.Set;

/** Resolves the navigator read projections currently exposed by a module. */
@FunctionalInterface
public interface PageNavigatorSourceCapabilityResolver {
    Set<NavigatorSourceCapability> capabilities(String moduleAlias);

    default boolean supports(String moduleAlias, NavigatorSourceCapability capability) {
        return capabilities(PlatformNameRules.requireModuleAlias(moduleAlias)).contains(capability);
    }

    /**
     * Whether the source's currently published contract can serve the requested in-place management UI.
     * Implementations must return false when the source action or editor contract cannot be proved.
     */
    default boolean supportsManagement(String moduleAlias, Set<String> actions, String editorSurface) {
        return false;
    }

    static PageNavigatorSourceCapabilityResolver unavailable() {
        return moduleAlias -> Set.of();
    }
}
