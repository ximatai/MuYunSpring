package net.ximatai.muyun.spring.platform.application;

import net.ximatai.muyun.spring.common.exception.DeclaredArgumentException;
import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.DiagnosticText;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/** Source-independent application declaration compiled from static application configuration. */
public record StaticApplicationDefinition(String alias, String title, int sortOrder) {
    public StaticApplicationDefinition {
        alias = PlatformNameRules.requireApplicationAlias(alias);
        title = title == null || title.isBlank() ? alias : title.trim();
        if (sortOrder < 0) {
            throw new DeclaredArgumentException(DiagnosticText.code("platform.static.diagnostic.static-application-sortorder-must-not-be-negative",
                    "static application sortOrder must not be negative: {alias}", Map.of("alias", String.valueOf(alias))));
        }
    }

    public static StaticApplicationDefinition of(String alias, String title, int sortOrder) {
        return new StaticApplicationDefinition(alias, title, sortOrder);
    }
}
