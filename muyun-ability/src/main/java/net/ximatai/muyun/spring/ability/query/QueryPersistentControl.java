package net.ximatai.muyun.spring.ability.query;

import java.util.List;

/**
 * A query-field condition rendered by default in a list's persistent query area.
 *
 * <p>The field remains authoritative for value type and supported operators. This only declares
 * presentation intent, allowing standard static and dynamic list renderers to compose the same
 * root-AND condition without page-specific frontend code.</p>
 */
public record QueryPersistentControl(String id, String title, QueryOperator operator, List<Object> defaultValues) {
    public QueryPersistentControl {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("persistent query id must not be blank");
        title = title == null || title.isBlank() ? id : title.trim();
        if (operator == null) throw new IllegalArgumentException("persistent query operator must not be null");
        defaultValues = defaultValues == null ? List.of() : List.copyOf(defaultValues);
    }

    public static QueryPersistentControl of(String id, String title, QueryOperator operator) {
        return new QueryPersistentControl(id, title, operator, List.of());
    }
}
