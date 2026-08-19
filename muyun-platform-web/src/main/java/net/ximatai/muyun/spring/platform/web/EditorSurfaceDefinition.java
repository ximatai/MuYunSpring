package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/** A named, module-owned editor contract for a non-template UI surface. */
public record EditorSurfaceDefinition(String key, ViewDefinition editor) {
    public EditorSurfaceDefinition {
        key = PlatformNameRules.requireIdentifier(key, "editor surface key");
        if (editor == null || editor.viewKind() != ModuleViewKind.FORM) {
            throw new IllegalArgumentException("editor surface requires a form editor");
        }
    }
}
