package net.ximatai.muyun.spring.platform.localization;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import java.util.List;
import java.util.Optional;

/** Producer-owned active configuration text facts; callers cannot manufacture originals. */
public interface ConfiguredTextSourceProvider {
    List<TextReference> currentSources(String moduleAlias);

    /** Indexed producers may override; default retains source conflict detection. */
    default Optional<TextReference> findCurrentSource(String moduleAlias, String sourceId) {
        TextReference result = null;
        for (var source : currentSources(moduleAlias)) {
            if (source.sourceKind() != TextReference.SourceKind.CONFIG || !source.sourceId().equals(sourceId)) continue;
            if (result != null && !result.equals(source))
                throw new IllegalStateException("Conflicting configured text source: " + sourceId);
            result = source;
        }
        return Optional.ofNullable(result);
    }
}
