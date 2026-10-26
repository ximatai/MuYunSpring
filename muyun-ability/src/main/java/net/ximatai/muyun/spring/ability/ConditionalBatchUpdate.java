package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.TableMeta;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.schema.PlatformDatabaseValueConverter;
import net.ximatai.muyun.spring.common.tenant.TenantContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Internal PostgreSQL storage primitive for server-owned field commands, shared by static and runtime tables.
 * Equality conditions are ANDed; null means IS NULL. Always targets exactly the current account partition
 * (no tenant means tenant_id IS NULL, including system/bypass contexts). Returns only IDs actually changed.
 * Requires at least one business predicate beyond tenant and soft-delete scope conditions.
 * Advances each row's version and update audit fields atomically. Does not run CRUD hooks, permissions,
 * field protection, cache invalidation or domain events: callers own those semantics, as for direct DAO writes.
 */
public final class ConditionalBatchUpdate {
    private static final Set<String> SCOPE_FIELDS = Set.of("tenantId", "deleted");
    private static final Set<String> MANAGED = Set.of("id", "tenantId", "version", "createdAt", "createdBy",
            "updatedAt", "updatedBy", "deleted", "deletedAt", "deletedBy");

    private ConditionalBatchUpdate() {}

    public static List<String> updateReturningIds(IDatabaseOperations<?> database, TableMeta table,
                                                  Map<String, Object> patch, Map<String, Object> conditions,
                                                  Instant now) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(now, "now");
        if (patch.isEmpty() || conditions.keySet().stream().noneMatch(field -> !SCOPE_FIELDS.contains(field))) {
            throw new IllegalArgumentException("batch update requires a patch and business conditions");
        }
        var values = new LinkedHashMap<String, Object>();
        var assignments = new ArrayList<String>();
        var predicates = new ArrayList<String>();
        patch.forEach((field, value) -> {
            if (MANAGED.contains(field)) throw new IllegalArgumentException("managed batch field: " + field);
            assignments.add(column(table, field) + " = " + bind(values, value));
        });
        assignments.add(column(table, "version") + " = coalesce(" + column(table, "version") + ", 0) + 1");
        assignments.add(column(table, "updatedAt") + " = " + bind(values, now));
        String actor = TenantContext.systemOperatorId()
                .orElseGet(() -> CurrentUserContext.currentUser().map(user -> user.userId()).orElse(null));
        if (actor != null) assignments.add(column(table, "updatedBy") + " = " + bind(values, actor));
        conditions.forEach((field, value) -> predicates.add(predicate(table, field, value, values)));
        predicates.add(predicate(table, "tenantId", TenantContext.currentTenantId().orElse(null), values));
        String schema = table.getSchema();
        if (schema == null || schema.isBlank()) schema = database.getDefaultSchemaName();
        String target = (schema == null || schema.isBlank() ? "" : quote(schema) + ".") + quote(table.getTableName());
        String sql = "update " + target + " set " + String.join(", ", assignments)
                + " where " + String.join(" and ", predicates) + " returning " + quote(table.getIdField().getColumnName())
                + " as changed_id";
        return database.query(sql, values).stream().map(row -> Objects.toString(row.get("changed_id"))).toList();
    }

    private static String predicate(TableMeta table, String field, Object value, Map<String, Object> values) {
        return column(table, field) + (value == null ? " is null" : " = " + bind(values, value));
    }

    private static String bind(Map<String, Object> values, Object value) {
        String name = "batch_" + values.size();
        values.put(name, new PlatformDatabaseValueConverter().toDatabaseValue(value));
        return ":" + name;
    }

    private static String column(TableMeta table, String field) {
        var meta = table.findByFieldName(field);
        if (meta == null) throw new IllegalArgumentException("unknown batch field: " + field);
        return quote(meta.getColumnName());
    }

    private static String quote(String identifier) {
        if (identifier == null || identifier.isBlank()) throw new IllegalArgumentException("empty SQL identifier");
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
