package net.ximatai.muyun.spring.common.i18n;

import java.util.LinkedHashMap;
import java.util.Map;

/** Language-independent display resources; resolving never changes the original or its arguments. */
public final class TextPresentations {
    private TextPresentations() { }

    public static TextPresentation of(TextReference source, Map<String, ?> arguments,
                                      TextResolver resolver, LanguageCatalog catalog) {
        Map<String, TextResources.Resolution> languages = new LinkedHashMap<>();
        for (var language : catalog.languages()) if (language.enabled())
            languages.put(language.tag(), resolver.resolve(source, arguments, language.tag()));
        return new TextPresentation(source, languages);
    }
}
