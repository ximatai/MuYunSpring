package net.ximatai.muyun.spring.platform.ui;

/** Optional source-owned CRUD affordance for a dynamic navigator level. */
public record PlatformPageNavigatorManagement(String editorSurface) {
    public PlatformPageNavigatorManagement {
        editorSurface = editorSurface == null || editorSurface.isBlank() ? null : editorSurface.trim();
    }
}
