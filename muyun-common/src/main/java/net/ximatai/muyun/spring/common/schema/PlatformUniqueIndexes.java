package net.ximatai.muyun.spring.common.schema;

import net.ximatai.muyun.database.core.builder.Column;
import net.ximatai.muyun.database.core.builder.Index;
import net.ximatai.muyun.database.core.builder.IndexColumn;
import net.ximatai.muyun.database.core.builder.TableWrapper;

import java.util.ArrayList;
import java.util.List;

public final class PlatformUniqueIndexes {
    private PlatformUniqueIndexes() {
    }

    public static void normalizeTenantUniqueIndexes(TableWrapper table) {
        if (table == null) {
            return;
        }
        List<Index> normalized = new ArrayList<>();
        for (Index index : table.getIndexes()) {
            if (!index.isUnique() || shouldKeepGlobal(index.getColumns())) {
                normalized.add(index);
                continue;
            }
            var columns = new ArrayList<IndexColumn>();
            columns.add(IndexColumn.asc(StandardEntitySchema.TENANT_ID_COLUMN));
            columns.addAll(index.getIndexColumns());
            var candidate = new Index(columns, true, index.getPredicate());
            if (index.getName() != null) candidate.named(index.getName());
            if (!containsIndex(normalized, candidate)) {
                normalized.add(candidate);
            }
        }
        table.getIndexes().clear();
        table.getIndexes().addAll(normalized);
        for (Column column : table.getColumns()) {
            if (column.isUnique() && !shouldKeepGlobal(List.of(column.getName()))) {
                column.setUnique(false);
                addTenantUniqueIndex(table, column.getName());
            }
        }
    }

    public static void addTenantUniqueIndex(TableWrapper table, String columnName) {
        addTenantUniqueIndex(table, List.of(columnName));
    }

    public static void addTenantUniqueIndex(TableWrapper table, List<String> columnNames) {
        addTenantUniqueIndex(table, columnNames, null, null);
    }

    public static void addTenantUniqueIndex(TableWrapper table, List<String> columnNames, String name, String predicate) {
        if (table == null || columnNames == null || columnNames.isEmpty()
                || columnNames.stream().anyMatch(column -> column == null || column.isBlank())
                || columnNames.contains(StandardEntitySchema.ID_COLUMN) && predicate == null) {
            return;
        }
        List<String> columns = columnNames.contains(StandardEntitySchema.TENANT_ID_COLUMN) ? columnNames : withTenant(columnNames);
        table.getIndexes().removeIf(index -> index.isUnique() && index.getColumns().equals(columnNames)
                && java.util.Objects.equals(index.getPredicate(), predicate) && !columns.equals(columnNames));
        Index candidate = new Index(columns, true).predicate(predicate);
        if (name != null) candidate.named(name);
        PlatformConditionalIndexes.requireAvailableName(table, candidate);
        if (!containsIndex(table.getIndexes(), candidate)) {
            table.getIndexes().add(candidate);
        }
    }

    private static boolean shouldKeepGlobal(List<String> columns) {
        return columns.contains(StandardEntitySchema.ID_COLUMN)
                || columns.contains(StandardEntitySchema.TENANT_ID_COLUMN);
    }

    private static List<String> withTenant(List<String> columns) {
        List<String> result = new ArrayList<>();
        result.add(StandardEntitySchema.TENANT_ID_COLUMN);
        result.addAll(columns);
        return List.copyOf(result);
    }

    private static boolean containsIndex(List<Index> indexes, Index candidate) {
        return indexes.stream().anyMatch(index -> index.isUnique() == candidate.isUnique()
                && index.getIndexColumns().equals(candidate.getIndexColumns())
                && java.util.Objects.equals(index.getPredicate(), candidate.getPredicate()));
    }
}
