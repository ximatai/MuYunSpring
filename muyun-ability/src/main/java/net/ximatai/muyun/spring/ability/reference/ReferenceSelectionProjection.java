package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

import java.util.Arrays;
import java.util.List;

/** An explicitly authorised, relative field path delivered after selecting a ONE reference. */
public record ReferenceSelectionProjection(List<String> path) {
    public ReferenceSelectionProjection {
        path = path == null ? List.of() : List.copyOf(path);
        if (path.isEmpty()) {
            throw new IllegalArgumentException("reference selection projection path must not be empty");
        }
        path.forEach(segment -> PlatformNameRules.requireFieldName(segment, "reference selection projection"));
    }

    public ReferenceSelectionProjection(String value) {
        this(Arrays.stream(value == null ? new String[0] : value.trim().split("\\."))
                .filter(segment -> !segment.isBlank()).toList());
    }

    public String key() {
        return String.join(".", path);
    }

    public String targetField() {
        return path.getLast();
    }
}
