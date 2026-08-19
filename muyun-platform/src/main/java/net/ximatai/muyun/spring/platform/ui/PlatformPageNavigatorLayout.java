package net.ximatai.muyun.spring.platform.ui;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Parsed navigator section of a page layout JSON document. */
public record PlatformPageNavigatorLayout(List<PlatformPageNavigatorLevel> levels,
                                          List<PlatformPageContextBinding> contextBindings) {
    public PlatformPageNavigatorLayout {
        levels = levels == null ? List.of() : List.copyOf(levels);
        contextBindings = contextBindings == null ? List.of() : List.copyOf(contextBindings);
        if (levels.isEmpty()) {
            throw new IllegalArgumentException("navigator requires at least one level");
        }
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int index = 0; index < levels.size(); index++) {
            if (indexes.putIfAbsent(levels.get(index).key(), index) != null) {
                throw new IllegalArgumentException("navigator level keys must be unique");
            }
        }
        for (PlatformPageContextBinding binding : contextBindings) {
            if (!"NAVIGATOR".equals(binding.source())) continue;
            Integer sourceIndex = indexes.get(binding.sourceKey());
            if (sourceIndex == null) {
                throw new IllegalArgumentException("page-context navigator source is unavailable: " + binding.sourceKey());
            }
            if ("NAVIGATOR_QUERY".equals(binding.target())) {
                Integer targetIndex = indexes.get(binding.targetNavigatorLevelKey());
                if (targetIndex == null || targetIndex <= sourceIndex) {
                    throw new IllegalArgumentException("navigator-query target must follow its source: "
                            + binding.targetNavigatorLevelKey());
                }
            }
        }
    }
}
