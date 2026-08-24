package net.ximatai.muyun.spring.ability.query;

import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.database.core.orm.SortDirection;
import net.ximatai.muyun.spring.common.option.OptionBinding;
import net.ximatai.muyun.spring.common.option.OptionFieldDefinition;
import net.ximatai.muyun.spring.common.option.OptionFieldResolver;
import net.ximatai.muyun.spring.common.option.OptionLoadResolver;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public record QuerySchema(String scopeName,
                          String entityAlias,
                          QuickSearch quickSearch,
                          List<Field> fields,
                          List<ExternalCriteria> externalCriteria,
                          List<DefaultSort> defaultSorts) {
    public QuerySchema {
        fields = fields == null ? List.of() : List.copyOf(fields);
        externalCriteria = externalCriteria == null ? List.of() : List.copyOf(externalCriteria);
        defaultSorts = defaultSorts == null ? List.of() : List.copyOf(defaultSorts);
        quickSearch = quickSearch == null ? QuickSearch.disabled() : quickSearch;
    }

    public static QuerySchema from(QueryDescriptor descriptor) {
        return from(descriptor, null);
    }

    public static QuerySchema from(QueryDescriptor descriptor, Class<?> modelClass) {
        Map<String, OptionFieldDefinition> optionFields = optionFields(modelClass);
        Map<String, String> optionTitleFields = optionTitleFields(modelClass);
        List<Field> fields = descriptor.fields().stream()
                .map(field -> Field.from(mergeOptionField(field, optionFields, optionTitleFields)))
                .toList();
        return new QuerySchema(
                descriptor.scopeName(),
                null,
                QuickSearch.from(descriptor, optionFields, optionTitleFields),
                fields,
                descriptor.externalCriteria().stream()
                        .map(ExternalCriteria::from)
                        .toList(),
                Arrays.stream(descriptor.defaultSorts())
                        .map(DefaultSort::from)
                        .toList()
        );
    }

    private static QueryField mergeOptionField(QueryField field,
                                               Map<String, OptionFieldDefinition> optionFields,
                                               Map<String, String> optionTitleFields) {
        OptionFieldDefinition definition = optionFields.get(field.fieldName());
        QueryField resolved = field.optionBinding() != null || definition == null
                ? field
                : field.withOptionField(definition);
        String optionTitleField = optionTitleFields.get(field.fieldName());
        return optionTitleField == null || resolved.optionTitleField() != null
                ? resolved
                : resolved.withOptionTitleField(optionTitleField);
    }

    private static Map<String, OptionFieldDefinition> optionFields(Class<?> modelClass) {
        if (modelClass == null) {
            return Map.of();
        }
        return OptionFieldResolver.resolve(modelClass).stream()
                .collect(Collectors.toUnmodifiableMap(
                        OptionFieldDefinition::fieldName,
                        Function.identity(),
                        (left, right) -> left
                ));
    }

    private static Map<String, String> optionTitleFields(Class<?> modelClass) {
        if (modelClass == null) return Map.of();
        return OptionLoadResolver.resolve(modelClass).stream()
                .filter(definition -> "title".equals(definition.optionItemField()))
                .collect(Collectors.toUnmodifiableMap(definition -> definition.sourceField(),
                        definition -> definition.outputField(), (first, ignored) -> first));
    }

    public record QuickSearch(boolean enabled,
                              List<String> fields,
                              List<Field> fieldSchemas) {
        public QuickSearch {
            fields = fields == null ? List.of() : List.copyOf(fields);
            fieldSchemas = fieldSchemas == null ? List.of() : List.copyOf(fieldSchemas);
        }

        static QuickSearch from(QueryDescriptor descriptor) {
            return from(descriptor, Map.of(), Map.of());
        }

        static QuickSearch from(QueryDescriptor descriptor,
                                Map<String, OptionFieldDefinition> optionFields,
                                Map<String, String> optionTitleFields) {
            List<Field> fieldSchemas = descriptor.quickSearchFields().stream()
                    .map(field -> Field.from(mergeOptionField(field, optionFields, optionTitleFields)))
                    .toList();
            List<String> fields = fieldSchemas.stream().map(Field::name).toList();
            return new QuickSearch(!fields.isEmpty(), fields, fieldSchemas);
        }

        static QuickSearch disabled() {
            return new QuickSearch(false, List.of(), List.of());
        }
    }

    public record Field(String name,
                        String title,
                        QueryValueType valueType,
                        List<QueryOperator> operators,
                        QueryOperator defaultOperator,
                        boolean quickSearch,
                        boolean sortable,
                        OptionBinding optionBinding,
                        OptionSelectionMode selectionMode,
                        String optionTitleField) {
        public Field {
            operators = operators == null ? List.of() : List.copyOf(operators);
            optionTitleField = optionTitleField == null || optionTitleField.isBlank() ? null : optionTitleField.trim();
        }

        static Field from(QueryField field) {
            return new Field(
                    field.fieldName(),
                    field.title(),
                    field.valueType(),
                    List.copyOf(field.operators()),
                    field.defaultOperator(),
                    field.quickSearch(),
                    field.sortable(),
                    field.optionBinding(),
                    field.selectionMode(),
                    field.optionTitleField()
            );
        }
    }

    public record ExternalCriteria(String key,
                                   String valueType,
                                   String providedBy) {
        static ExternalCriteria from(ExternalQueryCriterionDefinition definition) {
            return new ExternalCriteria(definition.key(), definition.valueType().name(), definition.valueSource().name());
        }

        static ExternalCriteria pageContextObject(String key) {
            return new ExternalCriteria(key, "OBJECT", "PAGE_CONTEXT");
        }
    }

    public record DefaultSort(String field,
                              boolean desc) {
        static DefaultSort from(Sort sort) {
            return new DefaultSort(sort.getField(), sort.getDirection() == SortDirection.DESC);
        }
    }
}
