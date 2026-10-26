package net.ximatai.muyun.spring.common.exception;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.i18n.TextResources;

import java.util.Map;
import java.util.Objects;

/** Invalid platform declaration with an original diagnostic and a producer-owned display source. */
public final class DeclarationValidationException extends IllegalArgumentException implements net.ximatai.muyun.spring.common.i18n.TextSourceAware {
    private final TextReference textSource;
    private final Map<String, Object> messageArgs;

    public DeclarationValidationException(TextReference source, Map<String, Object> arguments) {
        super(TextResources.format(Objects.requireNonNull(source, "source").originalText(), arguments));
        this.textSource = source;
        this.messageArgs = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    public DeclarationValidationException(net.ximatai.muyun.spring.common.i18n.DiagnosticText text) {
        this(text.textSource(), text.messageArgs());
    }

    @Override
    public TextReference textSource() { return textSource; }
    @Override
    public Map<String, Object> messageArgs() { return messageArgs; }
}
