package net.ximatai.muyun.spring.platform.web;

import java.util.Set;

/** Resolved in-place management affordance for a page navigator level. */
public record ResolvedPageNavigatorManagementDescriptor(String editorSurface,
                                                        Set<PageNavigatorManagementAction> actions) {
    static ResolvedPageNavigatorManagementDescriptor from(PageNavigatorManagementDefinition definition) {
        return definition == null ? null : new ResolvedPageNavigatorManagementDescriptor(definition.editorSurface(),
                definition.actions());
    }
}
