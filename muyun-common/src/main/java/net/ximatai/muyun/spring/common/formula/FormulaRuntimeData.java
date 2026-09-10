package net.ximatai.muyun.spring.common.formula;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class FormulaRuntimeData implements FormulaEvaluationContext {
    private final RowValue main;
    private final Map<String, List<RowValue>> tables;
    private final boolean strictFields;
    private final boolean typedFields;
    private final Set<FormulaFieldPath> knownFields;
    private final Set<String> knownTables;
    private final Map<FormulaFieldPath, FormulaFieldDefinition> fieldDefinitions;
    /**
     * Declared scalar reference reads.  These intentionally remain separate from child tables:
     * a dotted field only reads here when the caller compiled it as a reference path.
     */
    private final Map<FormulaFieldPath, Object> referenceValues;
    private final Set<FormulaFieldPath> referenceFields;
    private final java.util.function.Function<Map<String, Object>, Map<String, Object>> referenceValueResolver;
    private FormulaEvaluationScope changeScope = FormulaEvaluationScope.main();

    public FormulaRuntimeData(Map<String, Object> main, Map<String, List<Map<String, Object>>> tables) {
        this(main, tables, false, false, Set.of(), Map.of(), Map.of(), Set.of(), null);
    }

    private FormulaRuntimeData(
            Map<String, Object> main,
            Map<String, List<Map<String, Object>>> tables,
            boolean strictFields,
            boolean typedFields,
            Set<FormulaFieldPath> knownFields,
            Map<FormulaFieldPath, FormulaFieldDefinition> fieldDefinitions,
            Map<FormulaFieldPath, Object> referenceValues,
            Set<FormulaFieldPath> referenceFields,
            java.util.function.Function<Map<String, Object>, Map<String, Object>> referenceValueResolver
    ) {
        this.main = new RowValue(main == null ? new LinkedHashMap<>() : main);
        this.tables = new LinkedHashMap<>();
        if (tables != null) {
            tables.forEach((key, rows) -> this.tables.put(key, toRows(rows)));
        }
        this.strictFields = strictFields;
        this.typedFields = typedFields;
        this.knownFields = knownFields == null ? Set.of() : Set.copyOf(knownFields);
        this.knownTables = this.knownFields.stream()
                .map(FormulaFieldPath::tableKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        this.fieldDefinitions = fieldDefinitions == null ? Map.of() : Map.copyOf(fieldDefinitions);
        this.referenceValues = referenceValues == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(referenceValues));
        this.referenceFields = referenceFields == null ? Set.of() : Set.copyOf(referenceFields);
        this.referenceValueResolver = referenceValueResolver;
    }

    public static FormulaRuntimeData of(Map<String, Object> main) {
        return new FormulaRuntimeData(main, Map.of());
    }

    public static FormulaRuntimeData of(Map<String, Object> main, Map<String, List<Map<String, Object>>> tables) {
        return new FormulaRuntimeData(main, tables);
    }

    public static FormulaRuntimeData strict(
            Map<String, Object> main,
            Map<String, List<Map<String, Object>>> tables,
            Set<String> knownDataIndexes
    ) {
        Set<FormulaFieldPath> fields = new HashSet<>();
        if (knownDataIndexes != null) {
            knownDataIndexes.forEach(dataIndex -> fields.add(FormulaFieldPath.parse(dataIndex)));
        }
        return new FormulaRuntimeData(main, tables, true, false, fields, Map.of(), Map.of(), Set.of(), null);
    }

    public static FormulaRuntimeData typed(
            Map<String, Object> main,
            Map<String, List<Map<String, Object>>> tables,
            Collection<FormulaFieldDefinition> fields
    ) {
        Map<FormulaFieldPath, FormulaFieldDefinition> definitions = fields == null
                ? Map.of()
                : fields.stream().collect(Collectors.toMap(
                        FormulaFieldDefinition::fieldPath,
                        field -> field,
                        (left, right) -> {
                            throw new IllegalArgumentException(
                                    "duplicate formula field definition: " + left.fieldPath().dataIndex()
                            );
                        },
                        LinkedHashMap::new
                ));
        return new FormulaRuntimeData(main, tables, true, true, definitions.keySet(), definitions, Map.of(), Set.of(), null);
    }

    /**
     * Adds values for paths that have been explicitly compiled as read-only scalar references.
     * Arbitrary dotted payload keys still retain their established child-table semantics.
     */
    public static FormulaRuntimeData typed(
            Map<String, Object> main,
            Map<String, List<Map<String, Object>>> tables,
            Collection<FormulaFieldDefinition> fields,
            Map<String, Object> declaredReferenceValues
    ) {
        Map<FormulaFieldPath, FormulaFieldDefinition> definitions = fields == null ? Map.of() : fields.stream()
                .collect(Collectors.toMap(FormulaFieldDefinition::fieldPath, field -> field, (left, right) -> {
                    throw new IllegalArgumentException("duplicate formula field definition: " + left.fieldPath().dataIndex());
                }, LinkedHashMap::new));
        Map<FormulaFieldPath, Object> references = new LinkedHashMap<>();
        if (declaredReferenceValues != null) {
            declaredReferenceValues.forEach((path, value) -> {
                FormulaFieldPath parsed = FormulaFieldPath.parse(path);
                if (!definitions.containsKey(parsed)) {
                    throw new IllegalArgumentException("reference formula field is not declared: " + path);
                }
                references.put(parsed, value);
            });
        }
        return new FormulaRuntimeData(main, tables, true, true, definitions.keySet(), definitions, references,
                references.keySet(), null);
    }

    /**
     * Supplies declared reference values on demand.  The resolver sees the current committed
     * main-record values, so a preceding calculation that changes a reference root cannot leave
     * a downstream formula with a stale projection.
     */
    public static FormulaRuntimeData typed(
            Map<String, Object> main,
            Map<String, List<Map<String, Object>>> tables,
            Collection<FormulaFieldDefinition> fields,
            Collection<String> declaredReferencePaths,
            java.util.function.Function<Map<String, Object>, Map<String, Object>> referenceValueResolver
    ) {
        Map<FormulaFieldPath, FormulaFieldDefinition> definitions = fields == null ? Map.of() : fields.stream()
                .collect(Collectors.toMap(FormulaFieldDefinition::fieldPath, field -> field, (left, right) -> {
                    throw new IllegalArgumentException("duplicate formula field definition: " + left.fieldPath().dataIndex());
                }, LinkedHashMap::new));
        Set<FormulaFieldPath> references = declaredReferencePaths == null ? Set.of() : declaredReferencePaths.stream()
                .map(FormulaFieldPath::parse).collect(Collectors.toUnmodifiableSet());
        if (!definitions.keySet().containsAll(references)) {
            throw new IllegalArgumentException("reference formula field is not declared");
        }
        return new FormulaRuntimeData(main, tables, true, true, definitions.keySet(), definitions, Map.of(), references,
                referenceValueResolver);
    }

    /**
     * Binds a formula run to one submitted child row. The supplied map must be one of the rows
     * passed for {@code tableKey}; using identity here prevents an equal-looking sibling from
     * accidentally becoming the mutation source.
     */
    public FormulaRuntimeData withChangeScope(String tableKey, Map<String, Object> row) {
        if (tableKey == null || tableKey.isBlank() || row == null) {
            throw new FormulaEvaluationException("FORMULA_CHANGE_SCOPE_INVALID", "formula child change scope is required");
        }
        RowValue source = rows(tableKey).stream()
                .filter(candidate -> candidate.values == row)
                .findFirst()
                .orElseThrow(() -> new FormulaEvaluationException("FORMULA_CHANGE_SCOPE_INVALID",
                        "formula change row does not belong to table: " + tableKey));
        this.changeScope = FormulaEvaluationScope.row(tableKey, source);
        return this;
    }

    @Override
    public FormulaEvaluationScope changeScope() {
        return changeScope;
    }

    @Override
    public Object get(FormulaFieldPath fieldPath, FormulaEvaluationScope scope) {
        requireKnown(fieldPath);
        if (referenceFields.contains(fieldPath)) {
            return referenceValue(fieldPath, main.values);
        }
        if (fieldPath.tableKey() == null) {
            return main.get(fieldPath.fieldName(), strictFields && knownFields.isEmpty());
        }
        if (scope.row() == null || !Objects.equals(scope.tableKey(), fieldPath.tableKey())) {
            return null;
        }
        return requireRow(scope.row()).get(fieldPath.fieldName(), strictFields && knownFields.isEmpty());
    }

    @Override
    public FormulaFieldWriteResult set(FormulaFieldPath fieldPath, Object value, FormulaEvaluationScope scope) {
        return setDirect(fieldPath, value, scope);
    }

    @Override
    public FormulaEvaluationSession beginSession() {
        return new StagedFormulaEvaluationSession();
    }

    private FormulaFieldWriteResult setDirect(FormulaFieldPath fieldPath, Object value, FormulaEvaluationScope scope) {
        requireKnown(fieldPath);
        if (referenceFields.contains(fieldPath)) {
            throw new FormulaEvaluationException("FORMULA_REFERENCE_FIELD_READ_ONLY", fieldPath.dataIndex(),
                    "formula reference field is read-only: " + fieldPath.dataIndex());
        }
        Object writeValue = convertForWrite(fieldPath, value);
        if (fieldPath.tableKey() == null) {
            return new FormulaFieldWriteResult(
                    fieldPath,
                    main.set(fieldPath.fieldName(), writeValue, strictFields && knownFields.isEmpty())
            );
        }
        if (scope.row() == null || !Objects.equals(scope.tableKey(), fieldPath.tableKey())) {
            throw new FormulaEvaluationException(
                    "FORMULA_FIELD_NOT_WRITABLE",
                    fieldPath.dataIndex(),
                    "formula field is not writable in current scope: " + fieldPath.dataIndex()
            );
        }
        return new FormulaFieldWriteResult(
                fieldPath,
                requireRow(scope.row()).set(fieldPath.fieldName(), writeValue, strictFields && knownFields.isEmpty())
        );
    }

    @Override
    public List<RowValue> rows(String tableKey) {
        if (strictFields && !tables.containsKey(tableKey) && !knownTables.contains(tableKey)) {
            throw new FormulaEvaluationException(
                    "FORMULA_UNKNOWN_TABLE",
                    tableKey,
                    "unknown formula table: " + tableKey
            );
        }
        return tables.getOrDefault(tableKey, List.of());
    }

    private Object convertForWrite(FormulaFieldPath fieldPath, Object value) {
        FormulaFieldDefinition fieldDefinition = fieldDefinitions.get(fieldPath);
        if (fieldDefinition != null && !fieldDefinition.writable()) {
            throw new FormulaEvaluationException(
                    "FORMULA_FIELD_NOT_WRITABLE",
                    fieldPath.dataIndex(),
                    "formula field is not writable: " + fieldPath.dataIndex()
            );
        }
        return FormulaValueConverter.convertForWrite(fieldDefinition, value);
    }

    private void requireKnown(FormulaFieldPath fieldPath) {
        if (strictFields && !knownFields.isEmpty() && !knownFields.contains(fieldPath)) {
            throw new FormulaEvaluationException(
                    "FORMULA_UNKNOWN_FIELD",
                    fieldPath.dataIndex(),
                    "unknown formula field: " + fieldPath.dataIndex()
            );
        }
        if (strictFields && typedFields && !fieldDefinitions.containsKey(fieldPath)) {
            throw new FormulaEvaluationException(
                    "FORMULA_UNKNOWN_FIELD",
                    fieldPath.dataIndex(),
                    "unknown formula field: " + fieldPath.dataIndex()
            );
        }
    }

    private Object referenceValue(FormulaFieldPath fieldPath, Map<String, Object> currentMain) {
        if (referenceValueResolver == null) {
            return referenceValues.get(fieldPath);
        }
        Map<String, Object> values = referenceValueResolver.apply(
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(currentMain)));
        return values == null ? null : values.get(fieldPath.dataIndex());
    }

    private RowValue requireRow(Object row) {
        if (row instanceof RowValue rowValue) {
            return rowValue;
        }
        throw new FormulaEvaluationException("FORMULA_UNSUPPORTED_ROW", "unsupported formula row: " + row);
    }

    private static List<RowValue> toRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<RowValue> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            result.add(new RowValue(row == null ? new LinkedHashMap<>() : row));
        }
        return result;
    }

    static class RowValue {
        private final Map<String, Object> values;

        RowValue(Map<String, Object> values) {
            this.values = values;
        }

        Map<String, Object> copyValues() {
            return new LinkedHashMap<>(values);
        }

        void replaceWith(Map<String, Object> nextValues) {
            values.clear();
            values.putAll(nextValues);
        }

        Object get(String key, boolean strictFields) {
            if (strictFields && !values.containsKey(key)) {
                throw new FormulaEvaluationException("FORMULA_UNKNOWN_FIELD", key, "unknown formula field: " + key);
            }
            return values.get(key);
        }

        boolean set(String key, Object value, boolean strictFields) {
            if (strictFields && !values.containsKey(key)) {
                throw new FormulaEvaluationException("FORMULA_UNKNOWN_FIELD", key, "unknown formula field: " + key);
            }
            Object old = values.get(key);
            if (Objects.equals(old, value)) {
                return false;
            }
            values.put(key, value);
            return true;
        }
    }

    private final class StagedFormulaEvaluationSession extends FormulaEvaluationSession {
        private final Map<String, Object> mainValues;
        private final Map<String, List<StagedRowValue>> tableRows = new LinkedHashMap<>();
        private final Set<FormulaFieldPath> writtenFields = new LinkedHashSet<>();

        StagedFormulaEvaluationSession() {
            super(FormulaRuntimeData.this, FormulaRuntimeData.this, true);
            this.mainValues = main.copyValues();
        }

        @Override
        public Object get(FormulaFieldPath fieldPath, FormulaEvaluationScope scope) {
            requireKnown(fieldPath);
            if (referenceFields.contains(fieldPath)) {
                return referenceValue(fieldPath, mainValues);
            }
            if (fieldPath.tableKey() == null) {
                if (strictFields && knownFields.isEmpty() && !mainValues.containsKey(fieldPath.fieldName())) {
                    throw new FormulaEvaluationException(
                            "FORMULA_UNKNOWN_FIELD",
                            fieldPath.dataIndex(),
                            "unknown formula field: " + fieldPath.dataIndex()
                    );
                }
                return mainValues.get(fieldPath.fieldName());
            }
            if (scope.row() == null || !Objects.equals(scope.tableKey(), fieldPath.tableKey())) {
                return null;
            }
            return requireStagedRow(scope.row()).get(fieldPath.fieldName(), strictFields && knownFields.isEmpty());
        }

        @Override
        public FormulaFieldWriteResult set(FormulaFieldPath fieldPath, Object value, FormulaEvaluationScope scope) {
            requireKnown(fieldPath);
            if (referenceFields.contains(fieldPath)) {
                throw new FormulaEvaluationException("FORMULA_REFERENCE_FIELD_READ_ONLY", fieldPath.dataIndex(),
                        "formula reference field is read-only: " + fieldPath.dataIndex());
            }
            Object writeValue = convertForWrite(fieldPath, value);
            writtenFields.add(fieldPath);
            if (fieldPath.tableKey() == null) {
                if (strictFields && knownFields.isEmpty() && !mainValues.containsKey(fieldPath.fieldName())) {
                    throw new FormulaEvaluationException(
                            "FORMULA_UNKNOWN_FIELD",
                            fieldPath.dataIndex(),
                            "unknown formula field: " + fieldPath.dataIndex()
                    );
                }
                Object old = mainValues.get(fieldPath.fieldName());
                mainValues.put(fieldPath.fieldName(), writeValue);
                return new FormulaFieldWriteResult(fieldPath, !Objects.equals(old, writeValue));
            }
            if (scope.row() == null || !Objects.equals(scope.tableKey(), fieldPath.tableKey())) {
                throw new FormulaEvaluationException(
                        "FORMULA_FIELD_NOT_WRITABLE",
                        fieldPath.dataIndex(),
                        "formula field is not writable in current scope: " + fieldPath.dataIndex()
                );
            }
            return requireStagedRow(scope.row()).set(fieldPath.fieldName(), writeValue, strictFields && knownFields.isEmpty())
                    ? new FormulaFieldWriteResult(fieldPath, true)
                    : new FormulaFieldWriteResult(fieldPath, false);
        }

        @Override
        public List<?> rows(String tableKey) {
            if (strictFields && !tables.containsKey(tableKey) && !knownTables.contains(tableKey)) {
                throw new FormulaEvaluationException(
                        "FORMULA_UNKNOWN_TABLE",
                        tableKey,
                        "unknown formula table: " + tableKey
                );
            }
            return tableRows.computeIfAbsent(tableKey, this::copyRows);
        }

        @Override
        public FormulaEvaluationScope changeScope() {
            FormulaEvaluationScope source = FormulaRuntimeData.this.changeScope;
            if (source.row() == null) {
                return source;
            }
            List<RowValue> sourceRows = tables.getOrDefault(source.tableKey(), List.of());
            int index = sourceRows.indexOf(source.row());
            if (index < 0) {
                throw new FormulaEvaluationException("FORMULA_CHANGE_SCOPE_INVALID",
                        "formula change row does not belong to table: " + source.tableKey());
            }
            List<StagedRowValue> stagedRows = (List<StagedRowValue>) rows(source.tableKey());
            return FormulaEvaluationScope.row(source.tableKey(), stagedRows.get(index));
        }

        @Override
        public List<FormulaFieldWriteResult> commit() {
            List<FormulaFieldWriteResult> results = new ArrayList<>();
            Map<RowValue, Map<String, Object>> rowSnapshots = new IdentityHashMap<>();
            boolean mainCommitted = false;
            Map<String, Object> mainSnapshot = main.copyValues();
            try {
                for (Map.Entry<String, List<StagedRowValue>> entry : tableRows.entrySet()) {
                    List<RowValue> targetRows = tables.getOrDefault(entry.getKey(), List.of());
                    List<StagedRowValue> stagedRows = entry.getValue();
                    for (int i = 0; i < stagedRows.size() && i < targetRows.size(); i++) {
                        StagedRowValue stagedRow = stagedRows.get(i);
                        if (stagedRow.writtenFields().isEmpty()) {
                            continue;
                        }
                        RowValue targetRow = targetRows.get(i);
                        Map<String, Object> rowSnapshot = rowSnapshots.computeIfAbsent(targetRow, RowValue::copyValues);
                        boolean rowChanged = false;
                        try {
                            for (String fieldName : stagedRow.writtenFields()) {
                                FormulaFieldPath fieldPath = new FormulaFieldPath(entry.getKey(), fieldName);
                                Object old = targetRow.get(fieldName, strictFields && knownFields.isEmpty());
                                Object next = stagedRow.get(fieldName, strictFields && knownFields.isEmpty());
                                targetRow.set(fieldName, next, strictFields && knownFields.isEmpty());
                                rowChanged = true;
                                if (!Objects.equals(old, next)) {
                                    results.add(new FormulaFieldWriteResult(fieldPath, true));
                                }
                            }
                        } catch (RuntimeException ex) {
                            if (!rowChanged) {
                                rowSnapshots.remove(targetRow);
                            } else {
                                targetRow.replaceWith(rowSnapshot);
                            }
                            throw ex;
                        }
                    }
                }
                for (FormulaFieldPath fieldPath : writtenFields) {
                    if (fieldPath.tableKey() == null) {
                        Object nextValue = mainValues.get(fieldPath.fieldName());
                        boolean changed = main.set(fieldPath.fieldName(), nextValue, strictFields && knownFields.isEmpty());
                        mainCommitted = mainCommitted || changed;
                        if (changed) {
                            results.add(new FormulaFieldWriteResult(fieldPath, true));
                        }
                    }
                }
            } catch (RuntimeException ex) {
                rowSnapshots.forEach(RowValue::replaceWith);
                if (mainCommitted) {
                    main.replaceWith(mainSnapshot);
                }
                throw ex;
            }
            return results;
        }

        @Override
        public void rollback() {
            writtenFields.clear();
            tableRows.clear();
            mainValues.clear();
        }

        private List<StagedRowValue> copyRows(String tableKey) {
            return tables.getOrDefault(tableKey, List.of()).stream()
                    .map(row -> new StagedRowValue(row.copyValues()))
                    .toList();
        }

        private StagedRowValue requireStagedRow(Object row) {
            if (row instanceof StagedRowValue rowValue) {
                return rowValue;
            }
            throw new FormulaEvaluationException("FORMULA_UNSUPPORTED_ROW", "unsupported formula staged row: " + row);
        }
    }

    private static final class StagedRowValue extends RowValue {
        private final Set<String> writtenFields = new LinkedHashSet<>();

        StagedRowValue(Map<String, Object> values) {
            super(values);
        }

        @Override
        boolean set(String key, Object value, boolean strictFields) {
            boolean changed = super.set(key, value, strictFields);
            writtenFields.add(key);
            return changed;
        }

        Set<String> writtenFields() {
            return writtenFields;
        }
    }
}
