package net.ximatai.muyun.spring.platform.application;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.i18n.TextResources;
import java.util.Map;
import java.util.Objects;

/** Producer-owned display declaration. Parameters retain user statements and never become business commands. */
public record ApplicationConstructionText(TextReference source, Map<String, Object> arguments) {
    public ApplicationConstructionText {
        Objects.requireNonNull(source);
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
    public static ApplicationConstructionText code(String key, String original) {
        return code(key, original, Map.of());
    }
    public static ApplicationConstructionText code(String key, String original, Map<String, Object> arguments) {
        return new ApplicationConstructionText(TextReference.code("platform.construction." + key, original), arguments);
    }
    public String original() { return TextResources.format(source.originalText(), arguments); }
}
