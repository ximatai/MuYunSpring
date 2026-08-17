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

    static PageNavigatorSourceCapabilityResolver unavailable() {
        return moduleAlias -> Set.of();
    }
}
