package net.ximatai.muyun.spring.ability.query;

import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class QueryDescriptors {
    private static final Set<String> BOOLEAN_FIELDS = Set.of(
            "enabled",
            "systemManaged",
            "dataScopeEnabled",
            "globalDefault",
            "approvalEnabled",
            "autoTitle",
            "autoPopulate",
            "cloneable",
            "unitRequired",
            "moneyCurrencyRequired",
            "visible",
            "readOnly",
            "required",
            "requiredOverride",
            "uniqueField",
            "indexed",
            "sortableField",
            "titleField",
            "actionAuth",
            "dataAuth",
            "allowExternalValue",
            "defaultTemplate",
            "published",
            "defaultSet"
    );
    private static final Set<String> INTEGER_FIELDS = Set.of(
            "sortOrder",
            "versionNo",
            "currentVersionNo",
            "retryCount",
            "decimalScale",
            "defaultLength",
            "defaultPrecision",
            "defaultScale",
            "scale",
            "priority"
    );
    private static final Set<String> LONG_FIELDS = Set.of(
            "currentValue"
    );
    private static final Set<String> DECIMAL_FIELDS = Set.of(
            "rate",
            "factor",
            "factorToBase",
            "offsetToBase"
    );
    private static final Set<String> INSTANT_FIELDS = Set.of(
            "createdAt",
            "updatedAt",
            "deletedAt",
            "publishedAt"
    );
    private static final Set<String> DATE_TIME_FIELDS = Set.of(
            "effectiveFrom",
            "effectiveTo"
    );
    private static final Set<String> DATE_FIELDS = Set.of(
            "effectiveDate"
    );
    private static final Map<String, String> FIELD_TITLES = Map.ofEntries(
            Map.entry("id", "ID"),
            Map.entry("parentId", "上级"),
            Map.entry("tenantId", "租户"),
            Map.entry("applicationAlias", "应用别名"),
            Map.entry("moduleAlias", "模块别名"),
            Map.entry("metadataId", "元数据"),
            Map.entry("metadataFieldId", "元数据字段"),
            Map.entry("moduleMetadataFieldId", "模块字段"),
            Map.entry("relationId", "关系"),
            Map.entry("viewId", "视图"),
            Map.entry("categoryId", "分类"),
            Map.entry("categoryAlias", "分类别名"),
            Map.entry("schemeId", "方案"),
            Map.entry("ruleId", "规则"),
            Map.entry("alias", "别名"),
            Map.entry("code", "编码"),
            Map.entry("title", "名称"),
            Map.entry("enabled", "启用状态"),
            Map.entry("sortOrder", "排序号"),
            Map.entry("createdAt", "创建时间"),
            Map.entry("updatedAt", "更新时间"),
            Map.entry("deletedAt", "删除时间"),
            Map.entry("deletedBy", "删除人"),
            Map.entry("publishedAt", "发布时间"),
            Map.entry("effectiveDate", "生效日期"),
            Map.entry("effectiveFrom", "生效开始"),
            Map.entry("effectiveTo", "生效结束"),
            Map.entry("systemManaged", "系统托管"),
            Map.entry("published", "已发布"),
            Map.entry("defaultSet", "默认集合"),
            Map.entry("defaultTemplate", "默认模板"),
            Map.entry("visible", "可见"),
            Map.entry("readOnly", "只读"),
            Map.entry("required", "必填"),
            Map.entry("requiredOverride", "必填覆盖"),
            Map.entry("fieldName", "字段名"),
            Map.entry("columnName", "列名"),
            Map.entry("fieldSpecAlias", "字段类型"),
            Map.entry("fieldUiControlAlias", "字段 UI 类型"),
            Map.entry("actionCode", "动作编码"),
            Map.entry("permissionActionCode", "权限动作码"),
            Map.entry("entityAlias", "实体别名"),
            Map.entry("fromCurrencyCode", "来源币种"),
            Map.entry("toCurrencyCode", "目标币种"),
            Map.entry("rateTypeCode", "汇率类型"),
            Map.entry("rate", "汇率"),
            Map.entry("source", "来源"),
            Map.entry("numericCode", "数字编码"),
            Map.entry("symbol", "符号"),
            Map.entry("decimalScale", "小数位"),
            Map.entry("roundingMode", "舍入模式"),
            Map.entry("baseCurrencyCode", "本位币"),
            Map.entry("categoryKind", "分类类型"),
            Map.entry("employeeNo", "职员编号"),
            Map.entry("mobile", "手机号"),
            Map.entry("email", "邮箱")
    );
    private static final Set<String> QUICK_SEARCH_FIELDS = Set.of(
            "title",
            "code",
            "alias",
            "employeeNo",
            "mobile",
            "email",
            "fieldName",
            "columnName",
            "actionCode",
            "permissionActionCode",
            "generatedValue",
            "codeValue",
            "recycledValue",
            "numericCode",
            "symbol",
            "fromCurrencyCode",
            "toCurrencyCode",
            "rateTypeCode",
            "baseUnitCode",
            "fromUnitCode",
            "toUnitCode",
            "fixedUnitCode",
            "defaultUnitCode",
            "moneyFixedCurrencyCode",
            "moneyDefaultCurrencyCode",
            "moneyBaseCurrencyCode",
            "moneyRateTypeCode",
            "targetRelationCode"
    );

    private QueryDescriptors() {
    }

    public static QueryDescriptor fromModel(String scopeName,
                                            Class<?> modelClass,
                                            Collection<String> fields,
                                            Sort... defaultSorts) {
        if (modelClass == null) {
            throw new IllegalArgumentException("query model class must not be null");
        }
        QueryDescriptor.Builder builder = QueryDescriptor.builder(scopeName);
        fields.forEach(field -> builder.field(field(modelClass, field)));
        if (defaultSorts != null) {
            for (Sort sort : defaultSorts) {
                builder.defaultSort(sort);
            }
        }
        return builder.build();
    }

    private static QueryField field(String fieldName) {
        QueryValueType valueType = valueType(fieldName);
        return field(fieldName, valueType);
    }

    public static QueryField field(Class<?> modelClass, String fieldName) {
        QueryValueType valueType = findField(modelClass, fieldName)
                .map(QueryDescriptors::valueType)
                .orElseGet(() -> valueType(fieldName));
        return field(fieldName, valueType);
    }

    private static QueryField field(String fieldName, QueryValueType valueType) {
        QueryField field = switch (valueType) {
            case BOOLEAN -> QueryField.of(fieldName, valueType, QueryOperator.EQ);
            case INTEGER, LONG, DECIMAL, DATE, INSTANT, DATETIME -> QueryField.of(fieldName, valueType,
                    QueryOperator.EQ,
                    QueryOperator.IN,
                    QueryOperator.GT,
                    QueryOperator.GTE,
                    QueryOperator.LT,
                    QueryOperator.LTE,
                    QueryOperator.BETWEEN);
            case JSON -> QueryField.of(fieldName, valueType, QueryOperator.EQ);
            case STRING, TEXT -> QueryField.of(fieldName, valueType,
                    QueryOperator.EQ,
                    QueryOperator.LIKE,
                    QueryOperator.IN);
        };
        if (isSortableByConvention(fieldName)) {
            field = field.withSortable();
        }
        String title = FIELD_TITLES.get(fieldName);
        if (title != null) {
            field = field.withTitle(net.ximatai.muyun.spring.common.i18n.TextReference.code(
                    "query/field/" + fieldName + "/title", title));
        }
        if (isQuickSearchByConvention(fieldName, valueType)) {
            field = field.withQuickSearch();
        }
        return field;
    }

    private static QueryValueType valueType(Field field) {
        Class<?> type = box(field.getType());
        if (type == Boolean.class) {
            return QueryValueType.BOOLEAN;
        }
        if (type == Integer.class || type == Short.class || type == Byte.class) {
            return QueryValueType.INTEGER;
        }
        if (type == Long.class || type == BigInteger.class) {
            return QueryValueType.LONG;
        }
        if (type == BigDecimal.class || type == Double.class || type == Float.class) {
            return QueryValueType.DECIMAL;
        }
        if (type == LocalDate.class) {
            return QueryValueType.DATE;
        }
        if (type == Instant.class) {
            return QueryValueType.INSTANT;
        }
        if (type == LocalDateTime.class || type == OffsetDateTime.class || type == ZonedDateTime.class) {
            return QueryValueType.DATETIME;
        }
        if (CodeTitleEnum.class.isAssignableFrom(type) || type.isEnum()) {
            return QueryValueType.STRING;
        }
        return valueType(field.getName());
    }

    private static Optional<Field> findField(Class<?> modelClass, String fieldName) {
        Class<?> current = modelClass;
        while (current != null && current != Object.class) {
            try {
                return Optional.of(current.getDeclaredField(fieldName));
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return Optional.empty();
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        return type;
    }

    private static QueryValueType valueType(String fieldName) {
        if (BOOLEAN_FIELDS.contains(fieldName)) {
            return QueryValueType.BOOLEAN;
        }
        if (INTEGER_FIELDS.contains(fieldName) || fieldName.endsWith("Count")) {
            return QueryValueType.INTEGER;
        }
        if (LONG_FIELDS.contains(fieldName)) {
            return QueryValueType.LONG;
        }
        if (DECIMAL_FIELDS.contains(fieldName)) {
            return QueryValueType.DECIMAL;
        }
        if (INSTANT_FIELDS.contains(fieldName) || fieldName.endsWith("At")) {
            return QueryValueType.INSTANT;
        }
        if (DATE_TIME_FIELDS.contains(fieldName)) {
            return QueryValueType.DATETIME;
        }
        if (DATE_FIELDS.contains(fieldName)) {
            return QueryValueType.DATE;
        }
        return QueryValueType.STRING;
    }

    private static boolean isSortableByConvention(String fieldName) {
        return "sortOrder".equals(fieldName)
                || "title".equals(fieldName)
                || "code".equals(fieldName)
                || "alias".equals(fieldName)
                || "createdAt".equals(fieldName)
                || "updatedAt".equals(fieldName)
                || "publishedAt".equals(fieldName)
                || "effectiveDate".equals(fieldName)
                || "priority".equals(fieldName)
                || "versionNo".equals(fieldName)
                || "currentVersionNo".equals(fieldName);
    }

    private static boolean isQuickSearchByConvention(String fieldName, QueryValueType valueType) {
        return QUICK_SEARCH_FIELDS.contains(fieldName)
                && (valueType == QueryValueType.STRING || valueType == QueryValueType.TEXT);
    }
}
