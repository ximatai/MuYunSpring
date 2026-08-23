package net.ximatai.muyun.spring.platform.web;

/** Resolved in-place management affordance for a page navigator level. */
public record ResolvedPageNavigatorManagementDescriptor(String editorSurface) {
    static ResolvedPageNavigatorManagementDescriptor from(PageNavigatorManagementDefinition definition) {
        return definition == null ? null : new ResolvedPageNavigatorManagementDescriptor(definition.editorSurface());
    }
}
