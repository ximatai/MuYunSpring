package net.ximatai.muyun.spring.common.i18n;

import java.util.Map;

/** One resolution contract for code declarations and published online configuration text. */
@FunctionalInterface
public interface TextResolver {
    TextResources.Resolution resolve(TextReference source, Map<String, ?> arguments, String languageTag);

    /** Prepare a bounded presentation batch without changing source facts or execution state. */
    default TextResolver prepare(java.util.Collection<TextReference> sources) { return this; }

    default TextResources.Resolution resolve(TextReference source, Map<String, ?> arguments) {
        return resolve(source, arguments, LanguageContext.currentTag());
    }
}
