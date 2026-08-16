package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/** Resolved named editor contract available for explicit selection by another UI surface. */
public record ResolvedEditorSurfaceDescriptor(String key, ResolvedViewDescriptor editor) {
    public ResolvedEditorSurfaceDescriptor {
        key = PlatformNameRules.requireIdentifier(key, "editor surface key");
        if (editor == null || editor.viewKind() != ModuleViewKind.FORM) {
            throw new IllegalArgumentException("resolved editor surface requires a form editor");
        }
    }
}
