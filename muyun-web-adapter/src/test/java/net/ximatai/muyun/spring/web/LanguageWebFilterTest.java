package net.ximatai.muyun.spring.web;

import jakarta.servlet.DispatcherType;
import net.ximatai.muyun.spring.common.i18n.LanguageCatalog;
import net.ximatai.muyun.spring.common.i18n.LanguageContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.*;

class LanguageWebFilterTest {
    private final LanguageWebFilter filter = new LanguageWebFilter(LanguageCatalog.load(getClass().getClassLoader(), "zh-CN"));
    @Test
    void carriesLanguageAcrossRedispatchAndLeavesTenantAndOuterLanguageIntact() throws Exception {
        var request = new MockHttpServletRequest(); request.addHeader("Accept-Language", "en-US");
        var response = new MockHttpServletResponse();
        try (var tenant = TenantContext.use("tenant-a"); var outer = LanguageContext.use("zh-CN")) {
            filter.doFilter(request, response, (req, res) -> {
                assertThat(LanguageContext.currentTag()).isEqualTo("en");
                assertThat(TenantContext.currentTenantId()).contains("tenant-a");
            });
            assertThat(LanguageContext.currentTag()).isEqualTo("zh-CN");
            request.setDispatcherType(DispatcherType.ASYNC); request.removeHeader("Accept-Language"); request.addHeader("Accept-Language", "zh-CN");
            filter.doFilter(request, response, (req, res) -> assertThat(LanguageContext.currentTag()).isEqualTo("en"));
        }
        assertThat(LanguageContext.currentTag()).isNull();
        assertThat(response.getHeader("Content-Language")).isEqualTo("en");
        assertThat(response.getHeaders("Vary")).contains("Accept-Language");
    }
    @Test
    void releasesRequestLanguageAfterFailures() {
        var request = new MockHttpServletRequest(); request.addHeader("Accept-Language", "en");
        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {throw new IllegalStateException("failure");})).isInstanceOf(IllegalStateException.class);
        assertThat(LanguageContext.currentTag()).isNull();
    }
    @Test
    void mvcLocaleResolutionSharesTheEffectiveCatalogSelection() {
        var catalog = LanguageCatalog.load(getClass().getClassLoader(), "zh-CN");
        var resolver = new MuYunSpringLanguageConfiguration().localeResolver(catalog);
        var request = new MockHttpServletRequest(); request.addHeader("Accept-Language", "de-DE");
        assertThat(resolver.resolveLocale(request).toLanguageTag()).isEqualTo("zh-CN");
        try (var ignored = LanguageContext.use("en")) {
            assertThat(resolver.resolveLocale(request).toLanguageTag()).isEqualTo("en");
        }
        try (var ignored = LanguageContext.use("de-DE")) {
            assertThat(resolver.resolveLocale(request).toLanguageTag()).isEqualTo("zh-CN");
        }
    }

    @Test
    void deploymentConfigurationRestrictsBuiltInLanguagesAndValidatesTheDefault() {
        var configuration = new MuYunSpringLanguageConfiguration();
        var catalog = configuration.languageCatalog("en", " en ");
        assertThat(catalog.languages()).filteredOn(LanguageCatalog.Language::enabled)
                .extracting(LanguageCatalog.Language::tag).containsExactly("en");
        assertThat(catalog.select("zh-CN")).isEqualTo("en");
        assertThat(catalog.fallbackTags("en")).containsExactly("en", "zh-CN");
        assertThatThrownBy(() -> configuration.languageCatalog("zh-CN", "en"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Default language");
    }

    @Test
    void deploymentCanEnableAPreRegisteredLanguageAndSelectItAsDefaultInOneAssembly() {
        var configuration = new MuYunSpringLanguageConfiguration();
        var catalog = configuration.languageCatalog("fr", "fr");
        assertThat(catalog.defaultTag()).isEqualTo("fr");
        assertThat(catalog.languages()).filteredOn(LanguageCatalog.Language::enabled)
                .extracting(LanguageCatalog.Language::tag).containsExactly("fr");
        assertThat(catalog.select("en")).isEqualTo("fr");
        assertThat(catalog.fallbackTags("fr")).containsExactly("fr", "en", "zh-CN");
        assertThatThrownBy(() -> configuration.languageCatalog("fr", ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Default language");
    }

}
