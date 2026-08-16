package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

import java.util.List;

/** Serialized page-layout fact for one optional navigator column. */
public record PlatformPageNavigatorLevel(String key,
                                         String kind,
                                         String sourceModuleAlias,
                                         String title,
                                         String searchPlaceholder,
                                         List<PlatformPageNavigatorQueryBinding> queryBindings,
                                         List<PlatformPageNavigatorChildBinding> childBindings,
                                         PlatformPageNavigatorManagement management) {
    public PlatformPageNavigatorLevel {
        key = PlatformNameRules.requireFieldName(key, "navigator level key");
        if (!"TREE".equals(kind) && !"MICRO_LIST".equals(kind)) {
            throw new IllegalArgumentException("navigator level kind must be TREE or MICRO_LIST: " + kind);
        }
        sourceModuleAlias = PlatformNameRules.requireModuleAlias(sourceModuleAlias);
        title = title == null || title.isBlank() ? null : title.trim();
        searchPlaceholder = searchPlaceholder == null || searchPlaceholder.isBlank() ? null : searchPlaceholder.trim();
        queryBindings = queryBindings == null ? List.of() : List.copyOf(queryBindings);
        childBindings = childBindings == null ? List.of() : List.copyOf(childBindings);
    }
}
