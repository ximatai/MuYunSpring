package net.ximatai.muyun.spring.platform.ui;

import java.util.Set;

/** Optional source-owned CRUD affordance for a dynamic navigator level. */
public record PlatformPageNavigatorManagement(String editorSurface, Set<String> actions) {
    public PlatformPageNavigatorManagement {
        editorSurface = editorSurface == null || editorSurface.isBlank() ? null : editorSurface.trim();
        actions = actions == null ? null : Set.copyOf(actions);
    }
}
