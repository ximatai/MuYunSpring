package net.ximatai.muyun.spring.common.formula;

public record FormulaFieldDefinition(
        FormulaFieldPath fieldPath,
        FormulaValueType type,
        boolean required,
        boolean writable
) {
    public FormulaFieldDefinition {
        if (fieldPath == null || fieldPath.fieldName().isBlank()) {
            throw new IllegalArgumentException("formula field path is required");
        }
        type = type == null ? FormulaValueType.ANY : type;
    }

    public static FormulaFieldDefinition of(String dataIndex, FormulaValueType type) {
        return new FormulaFieldDefinition(FormulaFieldPath.parse(dataIndex), type, false, true);
    }

    public FormulaFieldDefinition asRequired() {
        return new FormulaFieldDefinition(fieldPath, type, true, writable);
    }

    public FormulaFieldDefinition readonly() {
        return new FormulaFieldDefinition(fieldPath, type, required, false);
    }

    /** Converts a supplied value using the same type and nullability contract as formula writes. */
    public Object normalizeValue(Object value) {
        return FormulaValueConverter.convertForWrite(this, value);
    }
}
