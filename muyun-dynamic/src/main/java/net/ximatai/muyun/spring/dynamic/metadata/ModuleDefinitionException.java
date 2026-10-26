package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.spring.common.i18n.DiagnosticText;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.i18n.TextSourceAware;
import java.util.Map;

public class ModuleDefinitionException extends RuntimeException implements TextSourceAware {
    private final TextReference textSource;
    private final Map<String, Object> messageArgs;

    public ModuleDefinitionException(String message) {
        super(message);
        textSource = null;
        messageArgs = Map.of();
    }

    public ModuleDefinitionException(String message, Throwable cause) {
        super(message, cause);
        // A wrapper may reuse a declaration only when it retains the exact diagnostic.
        var declared = cause instanceof TextSourceAware source && message != null && message.equals(cause.getMessage())
                ? source : null;
        textSource = declared == null ? null : declared.textSource();
        messageArgs = declared == null ? Map.of() : Map.copyOf(declared.messageArgs());
    }

    public ModuleDefinitionException(DiagnosticText text) {
        super(text.message());
        textSource = text.textSource();
        messageArgs = text.messageArgs();
    }

    public ModuleDefinitionException(DiagnosticText text, Throwable cause) {
        super(text.message(), cause);
        textSource = text.textSource();
        messageArgs = text.messageArgs();
    }

    @Override
    public TextReference textSource() {
        return textSource;
    }

    @Override
    public Map<String, Object> messageArgs() {
        return messageArgs;
    }
}
