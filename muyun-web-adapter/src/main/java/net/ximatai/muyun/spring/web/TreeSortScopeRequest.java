package net.ximatai.muyun.spring.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Scope-only context accepted by the tree sort endpoint. */
public record TreeSortScopeRequest(
        Map<String, Object> externalQueryValues,
        String navigatorHostModuleAlias,
        String navigatorTargetLevelKey
) {
    public TreeSortScopeRequest {
        boolean hasHost = navigatorHostModuleAlias != null && !navigatorHostModuleAlias.isBlank();
        boolean hasLevel = navigatorTargetLevelKey != null && !navigatorTargetLevelKey.isBlank();
        if (hasHost != hasLevel) {
            throw new IllegalArgumentException("tree sort scope requires both navigator host and level");
        }
        externalQueryValues = externalQueryValues == null || externalQueryValues.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(externalQueryValues));
    }

    public WebQueryRequest toQueryRequest() {
        return new WebQueryRequest(null, null, List.of(), null, Map.of(), List.of(),
                null, null, externalQueryValues, null, null, List.of(), null,
                navigatorHostModuleAlias, navigatorTargetLevelKey);
    }
}
