package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

import java.util.LinkedHashMap;
import java.util.Map;

/** Server-authoritative fields resolved from one opaque page selection. */
public record ResolvedPageSelectionContext(String selectionKind,
                                           String selectionKey,
                                           Map<String, PageContextValue> values) {
    public ResolvedPageSelectionContext {
        selectionKind = PlatformNameRules.requireFieldName(selectionKind, "page selection kind");
        if (selectionKey == null || selectionKey.isBlank()) {
            throw new IllegalArgumentException("page selection key must not be blank");
        }
        Map<String, PageContextValue> copied = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((field, value) -> copied.put(
                    PlatformNameRules.requireFieldName(field, "page selection field"),
                    value == null ? PageContextValue.absent() : value));
        }
        values = Map.copyOf(copied);
    }
}
