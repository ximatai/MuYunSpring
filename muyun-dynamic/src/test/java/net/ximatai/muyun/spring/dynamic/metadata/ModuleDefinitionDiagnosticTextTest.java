package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.LanguageCatalog;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.i18n.TextResources;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ModuleDefinitionDiagnosticTextTest {
    @Test
    void validatorDeclaresTechnicalFieldIdentityWithoutChangingTheDiagnostic() {
        var failure = catchThrowableOfType(ModuleDefinitionException.class,
                () -> new ModuleDefinitionValidator().validateField(FieldDefinition.string("createdAt", "用户标题")));
        assertThat(failure.getMessage()).isEqualTo("field name conflicts with standard field: createdAt");
        assertThat(failure.messageArgs()).containsEntry("fieldName", "createdAt");
        assertThat(failure.textSource().sourceKind()).isEqualTo(TextReference.SourceKind.CODE);
        var loader = getClass().getClassLoader();
        var resources = TextResources.load(loader, LanguageCatalog.load(loader, "zh-CN"));
        assertThat(resources.resolve(failure.textSource(), failure.messageArgs(), "zh-CN").text())
                .isEqualTo("字段名与标准字段冲突：createdAt");
        assertThat(resources.resolve(failure.textSource(), failure.messageArgs(), "en").text()).isEqualTo(failure.getMessage());
    }

    @Test
    void unchangedWrappersPreserveDeclaredSourcesAndExternalDiagnosticsStayLiteral() {
        var source = TextReference.code("test.dynamic.declaration", "Invalid reference: {field}");
        var original = PlatformErrors.badRequestText(source, Map.of("field", "raw {field}"));
        var wrapped = new ModuleDefinitionException(original.getMessage(), original);
        assertThat(wrapped.textSource()).isEqualTo(source);
        assertThat(wrapped.messageArgs()).containsEntry("field", "raw {field}");
        assertThat(wrapped.getCause()).isSameAs(original);
        var changed = new ModuleDefinitionException("Changed diagnostic", original);
        assertThat(changed.textSource()).isNull();
        assertThat(changed.messageArgs()).isEmpty();
        var external = new ModuleDefinitionException("custom rule {field}", new RuntimeException("third party"));
        assertThat(external.getMessage()).isEqualTo("custom rule {field}");
        assertThat(external.textSource()).isNull();
        assertThat(external.messageArgs()).isEmpty();
    }
}
