package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

import java.util.List;

/**
 * Read-only placement of an already declared detail relation beneath a list row.
 *
 * <p>It deliberately names only the relation and the fields to browse.  Relation ownership,
 * field rendering and aggregate-save semantics remain owned by the relation declaration.</p>
 */
public record PageListRelationExpansionDefinition(String relationCode, List<String> fields) {
    public PageListRelationExpansionDefinition {
        relationCode = PlatformNameRules.requireIdentifier(relationCode, "list relation expansion code");
        fields = fields == null ? List.of() : fields.stream()
                .map(field -> requireFieldName(field, "list relation expansion field"))
                .distinct()
                .toList();
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("list relation expansion requires at least one field: " + relationCode);
        }
    }

    private static String requireFieldName(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("invalid " + name + ": " + value);
        }
        return value.trim();
    }
}
