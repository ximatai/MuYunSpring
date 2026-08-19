package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/** Static declaration of a detail relation. It deliberately does not imply a query endpoint. */
public record PageDetailRelationDefinition(String code, String title, String targetEntityAlias,
                                           String parentBinding, boolean readOnly,
                                           boolean refreshOnDetailReload) {
    public PageDetailRelationDefinition {
        code = PlatformNameRules.requireIdentifier(code, "detail relation code");
        title = title == null || title.isBlank() ? null : title.trim();
        targetEntityAlias = PlatformNameRules.requireIdentifier(targetEntityAlias, "detail relation target entity alias");
        parentBinding = requireText(parentBinding, "detail relation parent binding");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
