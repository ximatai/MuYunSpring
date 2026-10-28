package net.ximatai.muyun.spring.common.schema;

import net.ximatai.muyun.database.core.builder.*;
import net.ximatai.muyun.database.core.builder.sql.SchemaBuildRules;
import net.ximatai.muyun.database.core.metadata.DBInfo;
import net.ximatai.muyun.spring.common.model.constraint.ConditionalIndexDefinition;
import net.ximatai.muyun.spring.common.model.constraint.IndexCondition;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Compiles the same typed predicate for both static and dynamic schema declarations. */
public final class PlatformConditionalIndexes {
    private PlatformConditionalIndexes() { }

    public static void add(TableWrapper table, ConditionalIndexDefinition definition, Function<String, String> columns) {
        validate(definition, field -> requireColumn(table, columns.apply(field)).getType());
        List<String> keys = definition.fields().stream().map(field -> requireColumn(table, columns.apply(field)).getName()).toList();
        String predicate = definition.conditions().stream().map(condition -> sql(table, condition, columns)).collect(Collectors.joining(" AND "));
        if (definition.unique()) {
            PlatformUniqueIndexes.addTenantUniqueIndex(table, keys, definition.name(), predicate);
        } else {
            Index index = new Index(keys, false).named(definition.name()).predicate(predicate);
            requireAvailableName(table, index);
            table.getIndexes().add(index);
        }
    }

    public static void validate(ConditionalIndexDefinition definition, Function<String, ColumnType> types) {
        for (String field : definition.fields()) {
            ColumnType type = Objects.requireNonNull(types.apply(field), "unknown index field: " + field);
            if (List.of(ColumnType.JSON, ColumnType.JSON_SET, ColumnType.SET, ColumnType.ARRAY,
                    ColumnType.VARCHAR_ARRAY, ColumnType.INT_ARRAY, ColumnType.UNKNOWN).contains(type)) {
                throw new IllegalArgumentException("conditional index requires a scalar field: " + field);
            }
        }
        for (IndexCondition condition : definition.conditions()) {
            ColumnType type = Objects.requireNonNull(types.apply(condition.field()), "unknown index condition field");
            if ((condition.operator() == IndexCondition.Operator.IS_TRUE || condition.operator() == IndexCondition.Operator.IS_FALSE)
                    && type != ColumnType.BOOLEAN) throw new IllegalArgumentException("boolean index condition requires a boolean field: " + condition.field());
            if (condition.operator() == IndexCondition.Operator.IN_CODES
                    && !List.of(ColumnType.VARCHAR, ColumnType.TEXT, ColumnType.LONGTEXT).contains(type)) {
                throw new IllegalArgumentException("IN_CODES index condition requires a text/code field: " + condition.field());
            }
        }
    }

    public static void requireAvailableName(TableWrapper table, Index candidate) {
        if (candidate.getName() != null && table.getIndexes().stream().anyMatch(index ->
                Objects.equals(index.getName(), candidate.getName()) && (!index.getIndexColumns().equals(candidate.getIndexColumns())
                        || index.isUnique() != candidate.isUnique() || !Objects.equals(index.getPredicate(), candidate.getPredicate())))) {
            throw new IllegalArgumentException("conflicting index declaration: " + candidate.getName());
        }
    }

    private static String sql(TableWrapper table, IndexCondition condition, Function<String, String> columns) {
        Column column = requireColumn(table, columns.apply(condition.field()));
        String name = SchemaBuildRules.quoteIdentifier(column.getName(), DBInfo.Type.POSTGRESQL);
        return switch (condition.operator()) {
            case IS_NULL -> name + " IS NULL";
            case IS_NOT_NULL -> name + " IS NOT NULL";
            case IS_TRUE, IS_FALSE -> {
                yield name + (condition.operator() == IndexCondition.Operator.IS_TRUE ? " = TRUE" : " = FALSE");
            }
            case IN_CODES -> {
                // IndexCondition admits literal codes only, never quotes, escapes or SQL expressions.
                yield name + " IN (" + condition.values().stream().map(value -> "'" + value + "'").collect(Collectors.joining(", ")) + ")";
            }
        };
    }

    private static Column requireColumn(TableWrapper table, String name) {
        if (table.getPrimaryKey() != null && Objects.equals(table.getPrimaryKey().getName(), name)) return table.getPrimaryKey();
        return table.getColumns().stream().filter(column -> Objects.equals(column.getName(), name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("conditional index requires a physical column: " + name));
    }
}
