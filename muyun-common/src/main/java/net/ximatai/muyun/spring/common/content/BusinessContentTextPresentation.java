package net.ximatai.muyun.spring.common.content;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Ephemeral reading labels for an already exposed original; never a business write or quality confirmation. */
public record BusinessContentTextPresentation(String originalText, Map<String, String> readings) {
    public BusinessContentTextPresentation {
        Objects.requireNonNull(originalText, "originalText");
        Map<String, String> normalized = new LinkedHashMap<>();
        if (readings != null) readings.forEach((language, text) -> {
            String tag = new ContentWriteContext.SourceInput(language, null).languageTag();
            if (tag == null || text == null || text.isBlank())
                throw new IllegalArgumentException("Content presentation requires a language and reading text");
            if (normalized.putIfAbsent(tag, text) != null)
                throw new IllegalArgumentException("Duplicate content presentation language");
        });
        readings = Map.copyOf(normalized);
    }
}
