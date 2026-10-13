package net.ximatai.muyun.spring.common.i18n;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class TextReferenceTest {
    @Test
    void sourceRevisionsAreIndependentOfLanguageAndChangeWithOriginalText() {
        var source = TextReference.code("orders.form.title", "订单");
        assertThat(TextReference.code(source.sourceId(), source.originalText())).isEqualTo(source);
        assertThat(TextReference.code(source.sourceId(), "采购订单").sourceVersion()).isNotEqualTo(source.sourceVersion());
        assertThat(TextReference.configured(source.sourceId(), source.originalText()).sourceKind())
                .isEqualTo(TextReference.SourceKind.CONFIG);
        assertThatThrownBy(() -> TextReference.code(" ", "订单")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void codeResolutionPreservesBusinessArgumentsAndNeverClaimsConfigurationSources() {
        var catalog = LanguageCatalog.load(getClass().getClassLoader(), "zh-CN");
        TextResolver texts = new TextResources(catalog, Map.of("en", Map.of("test.saved", "Saved {name}")));
        var source = TextReference.code("test.saved", "保存 {name}");
        var args = Map.of("name", "张三 $1\\title");
        assertThat(texts.resolve(source, args, "en").text()).isEqualTo("Saved 张三 $1\\title");
        var configured = TextReference.configured(source.sourceId(), source.originalText());
        assertThat(texts.resolve(configured, args, "en").text()).isEqualTo("保存 张三 $1\\title");
        assertThat(texts.resolve(configured, args, "en").missing()).isTrue();
        assertThat(source.originalText()).isEqualTo("保存 {name}");
    }
}
