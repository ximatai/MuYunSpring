package net.ximatai.muyun.spring.platform.localization;

import net.ximatai.muyun.spring.common.i18n.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import java.util.*;

/** Code and configuration sources share fallback and interpolation, with a bounded publication read. */
@Service
@Primary
public class PlatformTextResolver implements TextResolver {
    private final ObjectProvider<TextResources> codeResources;
    private final ConfigurationTranslationService translations;
    public PlatformTextResolver(ObjectProvider<TextResources> codeResources, ConfigurationTranslationService translations) {
        this.codeResources = codeResources;
        this.translations = translations;
    }
    @Override public TextResources.Resolution resolve(TextReference source, Map<String, ?> arguments, String languageTag) {
        return prepare(List.of(source)).resolve(source, arguments, languageTag);
    }
    @Override public TextResolver prepare(Collection<TextReference> sources) {
        var rows = translations.published(sources);
        var catalog = translations.languageCatalog();
        var code = codeResources.getIfAvailable(() -> TextResources.load(getClass().getClassLoader(), catalog));
        return (source, arguments, requested) -> {
            if (source.sourceKind() == TextReference.SourceKind.CODE) return code.resolve(source, arguments, requested);
            String selected = catalog.select(requested);
            for (String tag : catalog.fallbackTags(selected)) {
                var match = rows.stream().filter(row -> source.sourceId().equals(row.getSourceId()) &&
                        source.sourceVersion().equals(row.getSourceVersion()) && source.originalText().equals(row.getOriginalText()) &&
                        tag.equals(row.getLanguageTag())).findFirst();
                if (match.isPresent()) return new TextResources.Resolution(TextResources.format(match.get().getPublishedText(), arguments),
                        selected, tag, false, !selected.equals(tag));
            }
            return new TextResources.Resolution(TextResources.format(source.originalText(), arguments), selected, null, true, true);
        };
    }
}
