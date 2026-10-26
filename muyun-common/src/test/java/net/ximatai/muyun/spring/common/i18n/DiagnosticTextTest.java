package net.ximatai.muyun.spring.common.i18n;

import net.ximatai.muyun.spring.common.exception.DeclaredArgumentException;
import net.ximatai.muyun.spring.common.exception.DeclaredStateException;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticTextTest {
    @Test
    void declarationsCopyArgumentsAndPreserveLiteralTechnicalIdentityAndExceptionTypes() {
        var args = new HashMap<String, Object>(Map.of("field", "raw {field} $1\\name"));
        var text = DiagnosticText.code("test.diagnostic", "Invalid field: {field}", args);
        args.put("field", "changed");
        var argument = new DeclaredArgumentException(text);
        var state = new DeclaredStateException(text);
        assertThat(argument).isInstanceOf(IllegalArgumentException.class);
        assertThat(state).isInstanceOf(IllegalStateException.class);
        assertThat(argument.getMessage()).isEqualTo("Invalid field: raw {field} $1\\name").isEqualTo(state.getMessage());
        assertThat(argument.textSource()).isEqualTo(text.textSource()).isEqualTo(state.textSource());
        assertThat(argument.messageArgs()).isEqualTo(text.messageArgs()).containsEntry("field", "raw {field} $1\\name");
        assertThat(state.messageArgs()).isEqualTo(text.messageArgs());
        // Existing no-cause constructors permit callers to attach their original cause.
        var cause = new RuntimeException("external");
        assertThat(argument.initCause(cause).getCause()).isSameAs(cause);
    }
}
