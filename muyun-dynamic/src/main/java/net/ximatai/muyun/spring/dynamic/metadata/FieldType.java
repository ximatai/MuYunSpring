package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

public enum FieldType implements CodeTitleEnum {
    STRING(ColumnType.VARCHAR),
    TEXT(ColumnType.TEXT),
    INTEGER(ColumnType.INT),
    LONG(ColumnType.BIGINT),
    BOOLEAN(ColumnType.BOOLEAN),
    TIMESTAMP(ColumnType.TIMESTAMP),
    ZONED_TIMESTAMP(ColumnType.TIMESTAMP),
    DATE(ColumnType.DATE),
    DECIMAL(ColumnType.NUMERIC),
    JSON(ColumnType.JSON);

    private final ColumnType columnType;

    FieldType(ColumnType columnType) {
        this.columnType = columnType;
    }

    public ColumnType toColumnType() {
        return columnType;
    }

    @Override
    public String getCode() {
        return name();
    }

    @Override
    public String getTitle() {
        return name();
    }

    public FieldTemporalSemantics temporalSemantics() {
        return switch (this) {
            case STRING, TEXT, INTEGER, LONG, BOOLEAN, DECIMAL, JSON -> FieldTemporalSemantics.NONE;
            case DATE -> FieldTemporalSemantics.BUSINESS_DATE;
            case TIMESTAMP -> FieldTemporalSemantics.UTC_INSTANT;
            case ZONED_TIMESTAMP -> FieldTemporalSemantics.ZONED_INSTANT;
        };
    }

    public boolean isTemporal() {
        return temporalSemantics() != FieldTemporalSemantics.NONE;
    }

    public boolean isBusinessDate() {
        return temporalSemantics() == FieldTemporalSemantics.BUSINESS_DATE;
    }

    public boolean isUtcInstant() {
        return temporalSemantics() == FieldTemporalSemantics.UTC_INSTANT;
    }

    public boolean isZonedInstant() {
        return temporalSemantics() == FieldTemporalSemantics.ZONED_INSTANT;
    }
}
