package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.dynamic.metadata.ViewControlType;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;

import java.util.List;
import java.util.Set;

public final class FieldUiControlPresetCatalog {
    private FieldUiControlPresetCatalog() {
    }

    /**
     * The single platform support matrix for persisted field controls.  A control being seeded is
     * not enough to make it publishable: only renderer kinds in this set have a source-neutral
     * web form implementation and transport contract.  The web descriptor compiler consumes this
     * matrix before publishing a dynamic page or accepting a static form at startup.
     */
    public static final Set<ViewControlType> WEB_FORM_EXECUTABLE_RENDERERS = Set.of(
            ViewControlType.TEXT, ViewControlType.TEXTAREA, ViewControlType.NUMBER,
            ViewControlType.DECIMAL, ViewControlType.SWITCH, ViewControlType.SELECT,
            ViewControlType.MULTI_SELECT, ViewControlType.DATE, ViewControlType.DATETIME,
            ViewControlType.COLOR_PICKER, ViewControlType.JSON);

    public static List<FieldUiControl> fieldUiControls() {
        return List.of(
                fieldUiType("text", "输入框", "string", FieldUiControlValueShape.SCALAR, ViewControlType.TEXT),
                fieldUiType("password", "密码输入框", "string", FieldUiControlValueShape.SCALAR, ViewControlType.TEXT),
                fieldUiType("textarea", "文本域", "text", FieldUiControlValueShape.SCALAR, ViewControlType.TEXTAREA),
                fieldUiType("number", "数字", "decimal", FieldUiControlValueShape.SCALAR, ViewControlType.DECIMAL),
                fieldUiType("integer", "整数", "integer", FieldUiControlValueShape.SCALAR, ViewControlType.NUMBER),
                fieldUiType("file_size", "文件大小", "long", FieldUiControlValueShape.SCALAR, ViewControlType.NUMBER),
                fieldUiType("amount", "金额", "decimal", FieldUiControlValueShape.SCALAR, ViewControlType.DECIMAL),
                fieldUiType("percentage", "百分比", "decimal", FieldUiControlValueShape.SCALAR, ViewControlType.DECIMAL),
                fieldUiType("switch", "开关", "boolean", FieldUiControlValueShape.SCALAR, ViewControlType.SWITCH),
                fieldUiType("select", "下拉单选", "string", FieldUiControlValueShape.SCALAR, ViewControlType.SELECT),
                fieldUiType("multi_select", "下拉多选", "json", FieldUiControlValueShape.COLLECTION, ViewControlType.MULTI_SELECT),
                fieldUiType("date", "日期", "date", FieldUiControlValueShape.SCALAR, ViewControlType.DATE),
                fieldUiType("datetime", "日期时间", "datetime", FieldUiControlValueShape.SCALAR, ViewControlType.DATETIME),
                fieldUiType("color_picker", "颜色选择器", "string", FieldUiControlValueShape.SCALAR, ViewControlType.COLOR_PICKER),
                fieldUiType("date_time_with_time_zone", "日期时间（含时区）", "zoned_datetime", FieldUiControlValueShape.COMPOSITE, "dateTime", ViewControlType.DATETIME),
                fieldUiType("json", "JSON", "json", FieldUiControlValueShape.SCALAR, ViewControlType.JSON),
                fieldUiType("date_range", "日期区间", "date", FieldUiControlValueShape.COMPOSITE, "start", ViewControlType.DATE),
                fieldUiType("date_time_range", "日期时间区间", "datetime", FieldUiControlValueShape.COMPOSITE, "start", ViewControlType.DATETIME)
        );
    }

    /**
     * Platform-owned primitive field types.  These aliases are the stable vocabulary consumed by
     * metadata, UI type presets and the runtime compiler; they must be available before a dynamic
     * module is configured.
     */
    public static List<FieldSpec> fieldTypes() {
        FieldSpec string = fieldType("string", "短文本", FieldType.STRING, 256, null, null,
                DynamicQueryOperator.LIKE, "text");
        string.setSafeTargetFieldSpecAliases(Set.of("text"));
        return List.of(
                string,
                fieldType("text", "长文本", FieldType.TEXT, null, null, null, DynamicQueryOperator.LIKE, "textarea"),
                fieldType("integer", "整数", FieldType.INTEGER, null, null, null, DynamicQueryOperator.EQ, "integer"),
                fieldType("long", "长整数", FieldType.LONG, null, null, null, DynamicQueryOperator.EQ, "number"),
                fieldType("decimal", "小数", FieldType.DECIMAL, null, 18, 2, DynamicQueryOperator.EQ, "number"),
                fieldType("boolean", "布尔", FieldType.BOOLEAN, null, null, null, DynamicQueryOperator.EQ, "switch"),
                fieldType("date", "日期", FieldType.DATE, null, null, null, DynamicQueryOperator.EQ, "date"),
                fieldType("datetime", "日期时间", FieldType.TIMESTAMP, null, null, null, DynamicQueryOperator.EQ, "datetime"),
                fieldType("zoned_datetime", "带时区日期时间", FieldType.ZONED_TIMESTAMP, null, null, null, DynamicQueryOperator.EQ, "date_time_with_time_zone"),
                fieldType("json", "JSON", FieldType.JSON, null, null, null, DynamicQueryOperator.CONTAINS, "json"),
                fieldType("json_set", "JSON 集合", FieldType.JSON, null, null, null, DynamicQueryOperator.CONTAINS_ANY, "multi_select")
        );
    }

    public static List<FieldUiControlProperty> properties() {
        return List.of(
                attribute("text", "maxLength", "字数限制", "integer", null),
                attribute("text", "placeholder", "占位提示", "string", null),
                attribute("textarea", "rows", "显示行数", "integer", "4"),
                attribute("textarea", "placeholder", "占位提示", "string", null),
                attribute("number", "precision", "小数位数", "integer", "2"),
                attribute("number", "min", "最小值", "decimal", null),
                attribute("number", "max", "最大值", "decimal", null),
                attribute("amount", "precision", "小数位数", "integer", "2"),
                attribute("amount", "min", "最小值", "decimal", "0"),
                attribute("percentage", "precision", "小数位数", "integer", "2"),
                attribute("percentage", "min", "最小值", "decimal", "0"),
                attribute("percentage", "max", "最大值", "decimal", "100"),
                attribute("date", "format", "格式", "string", "YYYY-MM-DD"),
                attribute("datetime", "format", "格式", "string", "YYYY-MM-DD HH:mm:ss"),
                attribute("date_time_with_time_zone", "format", "格式", "string", "YYYY-MM-DD HH:mm:ss")
        );
    }

    public static List<FieldUiControlBinding> bindings() {
        return List.of(
                mapping("date_range", "end", "结束值", "date"),
                mapping("date_time_range", "end", "结束值", "datetime"),
                mapping("date_time_with_time_zone", "timeZone", "时区", "string")
        );
    }

    private static FieldUiControl fieldUiType(String alias,
                                              String title,
                                              String defaultFieldSpecAlias,
                                              FieldUiControlValueShape valueShape,
                                              ViewControlType controlType) {
        return fieldUiType(alias, title, defaultFieldSpecAlias, valueShape, null, controlType);
    }

    private static FieldUiControl fieldUiType(String alias,
                                              String title,
                                              String defaultFieldSpecAlias,
                                              FieldUiControlValueShape valueShape,
                                              String primaryValueKey,
                                              ViewControlType controlType) {
        FieldUiControl type = new FieldUiControl();
        type.setId(alias);
        type.setAlias(alias);
        type.setTitle(title);
        type.setDefaultFieldSpecAlias(defaultFieldSpecAlias);
        type.setValueShape(valueShape);
        type.setPrimaryValueKey(primaryValueKey);
        type.setQueryMode(FieldUiControlQueryMode.DEFAULT);
        // Keep the metadata vocabulary visible for future delivery, but do not advertise an
        // editor that the published web runtime cannot execute yet.
        type.setEnabled(WEB_FORM_EXECUTABLE_RENDERERS.contains(controlType)
                && (valueShape == FieldUiControlValueShape.SCALAR
                || (controlType == ViewControlType.MULTI_SELECT && valueShape == FieldUiControlValueShape.COLLECTION)));
        if ("date_range".equals(alias) || "date_time_range".equals(alias)) {
            type.setQueryMode(FieldUiControlQueryMode.BETWEEN);
        }
        type.setRendererType(controlType);
        return type;
    }

    private static FieldSpec fieldType(String alias, String title, FieldType runtimeType,
                                               Integer length, Integer precision, Integer scale,
                                               DynamicQueryOperator queryOperator, String defaultUiControlAlias) {
        FieldSpec type = new FieldSpec();
        type.setId(alias);
        type.setAlias(alias);
        type.setTitle(title);
        type.setFieldType(runtimeType);
        type.setDefaultLength(length);
        type.setDefaultPrecision(precision);
        type.setDefaultScale(scale);
        type.setDefaultQueryOperator(queryOperator);
        type.setDefaultUiControlAlias(defaultUiControlAlias);
        type.setEnabled(Boolean.TRUE);
        return type;
    }

    private static FieldUiControlProperty attribute(String fieldUiControlAlias,
                                                          String attributeAlias,
                                                          String title,
                                                          String valueFieldSpecAlias,
                                                          String defaultValue) {
        FieldUiControlProperty attribute = new FieldUiControlProperty();
        attribute.setId(catalogId("property", fieldUiControlAlias, attributeAlias));
        attribute.setFieldUiControlAlias(fieldUiControlAlias);
        attribute.setAttributeAlias(attributeAlias);
        attribute.setTitle(title);
        attribute.setValueFieldSpecAlias(valueFieldSpecAlias);
        attribute.setDefaultValue(defaultValue);
        return attribute;
    }

    private static FieldUiControlBinding mapping(String fieldUiControlAlias, String valueKey, String title,
                                                 String valueFieldSpecAlias) {
        FieldUiControlBinding mapping = new FieldUiControlBinding();
        mapping.setId(catalogId("binding", fieldUiControlAlias, valueKey));
        mapping.setFieldUiControlAlias(fieldUiControlAlias);
        mapping.setValueKey(valueKey);
        mapping.setValueFieldSpecAlias(valueFieldSpecAlias);
        mapping.setTitle(title);
        return mapping;
    }

    private static String catalogId(String kind, String fieldUiControlAlias, String key) {
        return "field-ui-" + kind + "-" + Integer.toUnsignedString(
                (fieldUiControlAlias + "." + key).hashCode(), 36);
    }
}
