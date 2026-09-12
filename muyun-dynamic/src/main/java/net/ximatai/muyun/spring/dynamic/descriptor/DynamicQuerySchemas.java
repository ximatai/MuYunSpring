package net.ximatai.muyun.spring.dynamic.descriptor;

import net.ximatai.muyun.spring.ability.query.QueryOperator;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.ability.query.QueryValueType;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class DynamicQuerySchemas {
    private DynamicQuerySchemas() {
    }

    public static QuerySchema from(String moduleAlias, DynamicEntityDescriptor descriptor, List<String> quickSearchFields) {
        return from(moduleAlias, descriptor, quickSearchFields, List.of());
    }

    public static QuerySchema from(String moduleAlias,
                                   DynamicEntityDescriptor descriptor,
                                   List<String> quickSearchFields,
                                   List<String> externalCriteriaKeys) {
        Set<String> quickSearchFieldSet = quickSearchFields == null
                ? Set.of()
                : new LinkedHashSet<>(quickSearchFields);
        List<QuerySchema.Field> fields = descriptor.fields().stream()
                .filter(DynamicQuerySchemas::queryable)
                .map(field -> field(field, quickSearchFieldSet.contains(field.fieldName()), descriptor.fields()))
                .toList();
        List<QuerySchema.Field> quickSearchFieldSchemas = descriptor.fields().stream()
                .filter(field -> quickSearchFieldSet.contains(field.fieldName()))
                .map(DynamicQuerySchemas::quickSearchField)
                .toList();
        return new QuerySchema(
                moduleAlias,
                descriptor.entityAlias(),
                new QuerySchema.QuickSearch(
                        !quickSearchFieldSchemas.isEmpty(),
                        quickSearchFieldSchemas.stream().map(QuerySchema.Field::name).toList(),
                        quickSearchFieldSchemas
                ),
                fields,
                externalCriteriaKeys == null ? List.of() : externalCriteriaKeys.stream()
                        .filter(key -> key != null && !key.isBlank())
                        .distinct()
                        .map(key -> new QuerySchema.ExternalCriteria(key, "OBJECT", "PAGE_CONTEXT"))
                        .toList(),
                List.of()
        );
    }

    private static QuerySchema.Field field(DynamicFieldDescriptor field,
                                           boolean quickSearch,
                                           List<DynamicFieldDescriptor> fields) {
        boolean queryable = queryable(field);
        return new QuerySchema.Field(
                field.fieldName(),
                field.title(),
                valueType(field.type()),
                queryable ? operators(field) : List.of(QueryOperator.LIKE),
                queryable ? QueryOperator.valueOf(field.query().defaultOperator()) : QueryOperator.LIKE,
                quickSearch,
                field.sortable(),
                field.optionBinding(),
                field.selectionMode(),
                optionTitleField(field, fields),
                reference(field)
        );
    }

    private static QuerySchema.Field quickSearchField(DynamicFieldDescriptor field) {
        return new QuerySchema.Field(
                field.fieldName(),
                field.title(),
                valueType(field.type()),
                List.of(QueryOperator.LIKE),
                QueryOperator.LIKE,
                true,
                field.sortable(),
                field.optionBinding(),
                field.selectionMode(),
                null,
                reference(field)
            );
    }

    private static QuerySchema.Reference reference(DynamicFieldDescriptor field) {
        DynamicReferenceDescriptor reference = field.reference();
        return reference == null ? null : new QuerySchema.Reference(reference.targetModuleAlias(),
                reference.cardinality(), reference.labelField());
    }

    private static String optionTitleField(DynamicFieldDescriptor source, List<DynamicFieldDescriptor> fields) {
        if (source.optionBinding() == null) {
            return null;
        }
        return fields.stream()
                .filter(field -> field.optionLoad() != null)
                .filter(field -> source.fieldName().equals(field.optionLoad().sourceField()))
                .filter(field -> "title".equals(field.optionLoad().optionItemField()))
                .map(DynamicFieldDescriptor::fieldName)
                .findFirst()
                .orElse(null);
    }

    private static boolean queryable(DynamicFieldDescriptor field) {
        return field.query() != null && field.query().queryable();
    }

    private static List<QueryOperator> operators(DynamicFieldDescriptor field) {
        return field.query().operators().stream()
                .map(QueryOperator::valueOf)
                .toList();
    }

    private static QueryValueType valueType(FieldType type) {
        return switch (type) {
            case STRING -> QueryValueType.STRING;
            case TEXT -> QueryValueType.TEXT;
            case BOOLEAN -> QueryValueType.BOOLEAN;
            case INTEGER -> QueryValueType.INTEGER;
            case LONG -> QueryValueType.LONG;
            case DECIMAL -> QueryValueType.DECIMAL;
            case TIMESTAMP, ZONED_TIMESTAMP -> QueryValueType.INSTANT;
            case DATE -> QueryValueType.DATE;
            case JSON -> QueryValueType.JSON;
        };
    }
}
