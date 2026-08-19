package net.ximatai.muyun.spring.platform.ui;

import java.util.Set;

/** Optional source-owned CRUD affordance for a dynamic navigator level. */
public record PlatformPageNavigatorManagement(String editorSurface, Set<String> actions) {
    private static final Set<String> SUPPORTED_ACTIONS = Set.of("CREATE", "UPDATE", "DELETE");

    public PlatformPageNavigatorManagement {
        editorSurface = editorSurface == null || editorSurface.isBlank() ? null : editorSurface.trim();
        actions = actions == null ? null : Set.copyOf(actions);
        if (actions != null) {
            actions.stream()
                    .filter(action -> !SUPPORTED_ACTIONS.contains(action))
                    .findFirst()
                    .ifPresent(action -> {
                        throw new IllegalArgumentException("navigator management action is unsupported: " + action);
                    });
        }
    }
}
