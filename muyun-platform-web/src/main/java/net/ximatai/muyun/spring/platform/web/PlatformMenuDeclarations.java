package net.ximatai.muyun.spring.platform.web;

import org.springframework.core.annotation.AnnotatedElementUtils;
import java.util.HashSet;
import java.util.List;

/** Shared scan-time validation for menu initialization and action compilation. */
final class PlatformMenuDeclarations {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private PlatformMenuDeclarations() {}

    static List<PlatformMenu> of(Class<?> type) {
        var direct = type.getDeclaredAnnotationsByType(PlatformMenu.class);
        var menus = direct.length > 0 ? List.of(direct)
                : List.copyOf(AnnotatedElementUtils.findMergedRepeatableAnnotations(type, PlatformMenu.class));
        var ids = new HashSet<String>();
        for (var menu : menus) {
            if (menu.accessMode() == net.ximatai.muyun.spring.common.platform.ActionAccessMode.ANONYMOUS_ALLOWED)
                throw new IllegalStateException("Module menu requires an authenticated user: " + type.getName());
            if (menus.size() > 1 && menu.id().isBlank())
                throw new IllegalStateException("Multiple @PlatformMenu entries require explicit ids: " + type.getName());
            if (!ids.add(menu.id().trim()))
                throw new IllegalStateException("Duplicate @PlatformMenu id: " + menu.id());
            if (menu.accessMode() != menus.getFirst().accessMode())
                throw new IllegalStateException("Menus of one module must share accessMode: " + type.getName());
            if (!menu.entryParamsJson().isBlank()) {
                try {
                    if (!JSON.readTree(menu.entryParamsJson()).isObject())
                        throw new IllegalArgumentException("entryParamsJson requires a JSON object");
                } catch (java.io.IOException | IllegalArgumentException exception) {
                    throw new IllegalStateException("Invalid menu entryParamsJson: " + type.getName(), exception);
                }
            }
        }
        return menus;
    }
}
