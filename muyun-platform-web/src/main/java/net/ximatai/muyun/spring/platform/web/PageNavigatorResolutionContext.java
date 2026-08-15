package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.module.ModuleKind;

/**
 * Request-scoped facts available while resolving page navigator candidates.
 *
 * <p>Future IAM implementations decide which declared levels are selectable from this context.
 * The browser never supplies or infers these facts.</p>
 */
public record PageNavigatorResolutionContext(String moduleAlias,
                                             ModuleKind moduleKind,
                                             CurrentUser currentUser,
                                             ResolvedModulePageDescriptor candidate) {
    public PageNavigatorResolutionContext {
        moduleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        if (moduleKind == null) throw new IllegalArgumentException("module kind must not be null");
        if (candidate == null) throw new IllegalArgumentException("page navigator candidate must not be null");
    }
}
