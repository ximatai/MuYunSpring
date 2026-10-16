package net.ximatai.muyun.spring.platform.exchange.model;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.i18n.TextReference;

import java.util.List;

public record ExcelColumnPlan(
        String fieldName,
        String title,
        boolean required,
        ExcelValueType valueType,
        List<String> dropdownOptions,
        TextReference titleSource
) {
    public ExcelColumnPlan {
        requireText(fieldName, "fieldName must not be blank");
        requireText(title, "title must not be blank");
        valueType = valueType == null ? ExcelValueType.TEXT : valueType;
        dropdownOptions = dropdownOptions == null ? List.of() : List.copyOf(dropdownOptions);
    }

    public ExcelColumnPlan(String fieldName, String title, boolean required, ExcelValueType valueType,
                           List<String> dropdownOptions) {
        this(fieldName, title, required, valueType, dropdownOptions, null);
    }

    public ExcelColumnPlan withTitle(TextReference source) {
        return new ExcelColumnPlan(fieldName, source.originalText(), required, valueType, dropdownOptions, source);
    }

    public ExcelColumnPlan(String fieldName, String title) {
        this(fieldName, title, false, ExcelValueType.TEXT, List.of());
    }

    public ExcelColumnPlan(String fieldName, String title, boolean required) {
        this(fieldName, title, required, ExcelValueType.TEXT, List.of());
    }

    public ExcelColumnPlan(String fieldName, String title, ExcelValueType valueType) {
        this(fieldName, title, false, valueType, List.of());
    }

    public ExcelColumnPlan(String fieldName, String title, ExcelValueType valueType, boolean required) {
        this(fieldName, title, required, valueType, List.of());
    }

    public ExcelColumnPlan(String fieldName,
                           String title,
                           ExcelValueType valueType,
                           boolean required,
                           List<String> dropdownOptions) {
        this(fieldName, title, required, valueType, dropdownOptions);
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new PlatformException(message);
        }
    }
}
