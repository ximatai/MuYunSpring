package net.ximatai.muyun.spring.common.i18n;

import java.util.Map;
import java.util.Objects;

/** A diagnostic declaration with literal arguments, independent of the display language. */
public record DiagnosticText(TextReference textSource, Map<String, Object> messageArgs) implements TextSourceAware {
    public DiagnosticText {
        Objects.requireNonNull(textSource, "textSource");
        messageArgs = messageArgs == null ? Map.of() : Map.copyOf(messageArgs);
    }

    public static DiagnosticText code(String key, String original, Map<String, Object> arguments) {
        return new DiagnosticText(TextReference.code(key, original), arguments);
    }

    public static DiagnosticText code(String key, String original) {
        return code(key, original, Map.of());
    }

    public String message() {
        return TextResources.format(textSource.originalText(), messageArgs);
    }
}
