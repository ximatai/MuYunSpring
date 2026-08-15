package net.ximatai.muyun.spring.platform.ui;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Parsed navigator section of a page layout JSON document. */
public record PlatformPageNavigatorLayout(List<PlatformPageNavigatorLevel> levels) {
    public PlatformPageNavigatorLayout {
        levels = levels == null ? List.of() : List.copyOf(levels);
        if (levels.isEmpty()) {
            throw new IllegalArgumentException("navigator requires at least one level");
        }
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int index = 0; index < levels.size(); index++) {
            if (indexes.putIfAbsent(levels.get(index).key(), index) != null) {
                throw new IllegalArgumentException("navigator level keys must be unique");
            }
        }
        for (int index = 0; index < levels.size(); index++) {
            for (PlatformPageNavigatorChildBinding binding : levels.get(index).childBindings()) {
                Integer childIndex = indexes.get(binding.childLevelKey());
                if (childIndex == null || childIndex <= index) {
                    throw new IllegalArgumentException("navigator child level must follow its parent: "
                            + binding.childLevelKey());
                }
            }
        }
    }
}
