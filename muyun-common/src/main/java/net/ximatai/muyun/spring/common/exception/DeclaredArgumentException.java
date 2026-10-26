package net.ximatai.muyun.spring.common.exception;

import net.ximatai.muyun.spring.common.i18n.DiagnosticText;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.i18n.TextSourceAware;
import java.util.Map;

/** Preserves the IllegalArgumentException contract while declaring the diagnostic at its producer. */
public class DeclaredArgumentException extends IllegalArgumentException implements TextSourceAware {
    private final DiagnosticText text;

    public DeclaredArgumentException(DiagnosticText text) {
        super(text.message());
        this.text = text;
    }

    @Override
    public TextReference textSource() {
        return text.textSource();
    }

    @Override
    public Map<String, Object> messageArgs() {
        return text.messageArgs();
    }
}
