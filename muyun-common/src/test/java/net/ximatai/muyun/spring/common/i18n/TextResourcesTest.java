package net.ximatai.muyun.spring.common.i18n;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class TextResourcesTest {
    private final LanguageCatalog catalog = LanguageCatalog.load(getClass().getClassLoader(), "zh-CN");
    @Test
    void loadsUtf8DeploymentResourcesAndUsesExplicitContext() {
        var resources = TextResources.load(getClass().getClassLoader(), catalog);
        assertThat(resources.resolve("platform.language.label", null, Map.of()).text()).isEqualTo("界面语言");
        try (var ignored = LanguageContext.use("en")) {
            assertThat(resources.resolve("platform.language.label", null, Map.of()).text()).isEqualTo("Interface language");
        }
    }
    @Test
    void preservesFallbackDiagnosticsAndLiteralNamedParameterValues() {
        var resources = new TextResources(catalog, Map.of("zh-CN", Map.of("only.original", "保存 {count} 项：{title}")));
        var resolution = resources.resolve("only.original", "unused", Map.of("count", 2, "title", "$1\\demo"), "en");
        assertThat(resolution.text()).isEqualTo("保存 2 项：$1\\demo");
        assertThat(resolution.requestedTag()).isEqualTo("en");
        assertThat(resolution.resourceTag()).isEqualTo("zh-CN");
        assertThat(resolution.fallback()).isTrue();
        assertThat(resolution.missing()).isFalse();
        var missing = resources.resolve("missing", "原文 {count}", Map.of("count", 2), "en");
        assertThat(missing.text()).isEqualTo("原文 2");
        assertThat(missing.missing()).isTrue();
        assertThat(missing.resourceTag()).isNull();
    }
}
