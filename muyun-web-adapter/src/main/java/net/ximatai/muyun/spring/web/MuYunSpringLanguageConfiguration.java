package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.common.i18n.LanguageCatalog;
import net.ximatai.muyun.spring.common.i18n.LanguageContext;
import net.ximatai.muyun.spring.common.i18n.TextResources;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Arrays;

@Configuration
public class MuYunSpringLanguageConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public LanguageCatalog languageCatalog(@Value("${muyun.i18n.default-language:zh-CN}") String defaultTag,
                                          @Value("${muyun.i18n.enabled-languages:}") String enabledLanguages) {
        var catalog = LanguageCatalog.load(getClass().getClassLoader(), defaultTag);
        return enabledLanguages.isBlank() ? catalog : catalog.withEnabledLanguages(
                Arrays.stream(enabledLanguages.split(",")).map(String::trim).toList());
    }
    @Bean
    @ConditionalOnMissingBean
    public TextResources textResources(LanguageCatalog catalog) { return TextResources.load(getClass().getClassLoader(), catalog); }
    @Bean(name = DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME)
    @ConditionalOnMissingBean(name = DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME)
    public LocaleResolver localeResolver(LanguageCatalog catalog) {
        return new AcceptHeaderLocaleResolver() {
            @Override
            public Locale resolveLocale(HttpServletRequest request) {
                String tag = LanguageContext.currentTag();
                return Locale.forLanguageTag(catalog.select(tag == null ? request.getHeader("Accept-Language") : tag));
            }
        };
    }
    @Bean
    @ConditionalOnMissingBean
    public LanguageWebFilter languageWebFilter(LanguageCatalog catalog) { return new LanguageWebFilter(catalog); }
}
