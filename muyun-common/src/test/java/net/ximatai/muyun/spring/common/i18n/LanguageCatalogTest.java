package net.ximatai.muyun.spring.common.i18n;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class LanguageCatalogTest {
    private final LanguageCatalog catalog = LanguageCatalog.load(getClass().getClassLoader(), "zh-CN");
    @Test
    void negotiatesRegisteredLanguageByQualityAndFallsBackForInvalidInput() {
        assertThat(catalog.select("de-DE;q=1,en-US;q=0.8,zh-CN;q=0.5")).isEqualTo("en");
        assertThat(catalog.select("en;q=0,zh-CN;q=0.5")).isEqualTo("zh-CN");
        assertThat(catalog.select("invalid_\nheader")).isEqualTo("zh-CN");
        assertThat(catalog.fallbackTags("en")).containsExactly("en", "zh-CN");
        assertThat(catalog.languages()).extracting(LanguageCatalog.Language::nativeName).contains("简体中文", "English");
    }
    @Test
    void supportsAdditionalTagsWithoutBusinessEnumsAndRejectsBrokenFallbacks() {
        var extended = new LanguageCatalog("zh-CN", List.of(
                new LanguageCatalog.Language("zh-CN", "中文", null, "ltr"),
                new LanguageCatalog.Language("fr", "Français", "zh-CN", "ltr")));
        assertThat(extended.select("fr-CA")).isEqualTo("fr");
        assertThat(extended.fallbackTags("fr")).containsExactly("fr", "zh-CN");
        assertThatThrownBy(() -> new LanguageCatalog("en", List.of(
                new LanguageCatalog.Language("en", "English", "fr", "ltr"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unregistered fallback");
        assertThatThrownBy(() -> new LanguageCatalog("en", List.of(
                new LanguageCatalog.Language("en", "English", "fr", "ltr"),
                new LanguageCatalog.Language("fr", "Français", "en", "ltr"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cycle");
    }    @Test
    void excludesDisabledLanguagesFromSelectionButKeepsTheirFallbackResources() {
        var catalog = new LanguageCatalog("en", List.of(
                new LanguageCatalog.Language("en", "English", "zh-CN", "ltr", true),
                new LanguageCatalog.Language("zh-CN", "中文", null, "ltr", false)));
        assertThat(catalog.select("zh-CN")).isEqualTo("en");
        assertThat(catalog.fallbackTags("en")).containsExactly("en", "zh-CN");
        assertThatThrownBy(() -> new LanguageCatalog("zh-CN", catalog.languages()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Default language");
    }

}
