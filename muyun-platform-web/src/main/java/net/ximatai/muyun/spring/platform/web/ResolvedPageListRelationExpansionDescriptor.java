package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

import java.util.List;

/** Source-neutral list-row expansion intent; the referenced relation supplies all field semantics. */
public record ResolvedPageListRelationExpansionDescriptor(String relationCode, List<String> fields) {
    public ResolvedPageListRelationExpansionDescriptor {
        relationCode = PlatformNameRules.requireIdentifier(relationCode, "resolved list relation expansion code");
        fields = fields == null ? List.of() : fields.stream()
                .map(field -> requireFieldName(field, "resolved list relation expansion field"))
                .distinct()
                .toList();
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("resolved list relation expansion requires at least one field: " + relationCode);
        }
    }

    private static String requireFieldName(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("invalid " + name + ": " + value);
        }
        return value.trim();
    }
}
