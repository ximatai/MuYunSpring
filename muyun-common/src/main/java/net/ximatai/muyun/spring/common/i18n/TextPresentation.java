package net.ximatai.muyun.spring.common.i18n;

import java.util.Map;
import java.util.Objects;

/** Presentation resources accompanying an immutable original; consumers may switch language without replacing drafts. */
public record TextPresentation(TextReference source, Map<String, TextResources.Resolution> languages) {
    public TextPresentation {
        Objects.requireNonNull(source, "source");
        languages = Map.copyOf(languages);
    }
}
