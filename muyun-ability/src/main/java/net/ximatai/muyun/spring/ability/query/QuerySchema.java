package net.ximatai.muyun.spring.ability.query;

import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.database.core.orm.SortDirection;
import net.ximatai.muyun.spring.common.option.OptionBinding;
import net.ximatai.muyun.spring.common.option.OptionFieldDefinition;
import net.ximatai.muyun.spring.common.option.OptionFieldResolver;
import net.ximatai.muyun.spring.common.option.OptionLoadResolver;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;

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
                          List<DefaultSort> defaultSorts,
                          QueryCriteriaComposition criteriaComposition) {
    public QuerySchema {
        fields = fields == null ? List.of() : List.copyOf(fields);
        externalCriteria = externalCriteria == null ? List.of() : List.copyOf(externalCriteria);
        defaultSorts = defaultSorts == null ? List.of() : List.copyOf(defaultSorts);
        quickSearch = quickSearch == null ? QuickSearch.disabled() : quickSearch;
        criteriaComposition = criteriaComposition == null ? QueryCriteriaComposition.TREE : criteriaComposition;
    }

    /** Compatibility constructor. Ordinary query scopes support nested criteria by default. */
    public QuerySchema(String scopeName, String entityAlias, QuickSearch quickSearch, List<Field> fields,
                       List<ExternalCriteria> externalCriteria, List<DefaultSort> defaultSorts) {
        this(scopeName, entityAlias, quickSearch, fields, externalCriteria, defaultSorts,
                QueryCriteriaComposition.TREE);
    }

    public QuerySchema withCriteriaComposition(QueryCriteriaComposition composition) {
        return new QuerySchema(scopeName, entityAlias, quickSearch, fields, externalCriteria, defaultSorts, composition);
    }

    public static QuerySchema from(QueryDescriptor descriptor) {
        return from(descriptor, null);
    }

    public static QuerySchema from(QueryDescriptor descriptor, Class<?> modelClass) {
        Map<String, OptionFieldDefinition> optionFields = optionFields(modelClass);
        Map<String, String> optionTitleFields = optionTitleFields(modelClass);
        Map<String, ReferencePlan> referencePlans = referencePlans(modelClass);
        List<Field> fields = descriptor.fields().stream()
                .map(field -> Field.from(mergeOptionField(field, optionFields, optionTitleFields),
                        referencePlans.get(field.fieldName())))
                .toList();
        return new QuerySchema(
                descriptor.scopeName(),
                null,
                QuickSearch.from(descriptor, optionFields, optionTitleFields, referencePlans),
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

    private static Map<String, ReferencePlan> referencePlans(Class<?> modelClass) {
        if (modelClass == null) return Map.of();
        return StaticReferenceResolver.plans(modelClass).stream()
                .collect(Collectors.toUnmodifiableMap(ReferencePlan::sourceField, Function.identity(),
                        (left, right) -> left));
    }

    public record QuickSearch(boolean enabled,
                              List<String> fields,
                              List<Field> fieldSchemas) {
        public QuickSearch {
            fields = fields == null ? List.of() : List.copyOf(fields);
            fieldSchemas = fieldSchemas == null ? List.of() : List.copyOf(fieldSchemas);
        }

        static QuickSearch from(QueryDescriptor descriptor) {
            return from(descriptor, Map.of(), Map.of(), Map.of());
        }

        static QuickSearch from(QueryDescriptor descriptor,
                                Map<String, OptionFieldDefinition> optionFields,
                                Map<String, String> optionTitleFields,
                                Map<String, ReferencePlan> referencePlans) {
            List<Field> fieldSchemas = descriptor.quickSearchFields().stream()
                    .map(field -> Field.from(mergeOptionField(field, optionFields, optionTitleFields),
                            referencePlans.get(field.fieldName())))
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
                        String optionTitleField,
                        Reference reference,
                        PersistentControl persistentControl) {
        public Field {
            operators = operators == null ? List.of() : List.copyOf(operators);
            optionTitleField = optionTitleField == null || optionTitleField.isBlank() ? null : optionTitleField.trim();
        }

        /** Compatibility constructor for callers that do not declare a reference selection contract. */
        public Field(String name, String title, QueryValueType valueType, List<QueryOperator> operators,
                     QueryOperator defaultOperator, boolean quickSearch, boolean sortable,
                     OptionBinding optionBinding, OptionSelectionMode selectionMode, String optionTitleField) {
            this(name, title, valueType, operators, defaultOperator, quickSearch, sortable, optionBinding,
                    selectionMode, optionTitleField, null, null);
        }

        /** Compatibility constructor for a reference field without a persistent control. */
        public Field(String name, String title, QueryValueType valueType, List<QueryOperator> operators,
                     QueryOperator defaultOperator, boolean quickSearch, boolean sortable,
                     OptionBinding optionBinding, OptionSelectionMode selectionMode, String optionTitleField,
                     Reference reference) {
            this(name, title, valueType, operators, defaultOperator, quickSearch, sortable, optionBinding,
                    selectionMode, optionTitleField, reference, null);
        }

        static Field from(QueryField field) {
            return from(field, null);
        }

        static Field from(QueryField field, ReferencePlan referencePlan) {
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
                    field.optionTitleField(),
                    Reference.from(referencePlan, field.reference()),
                    PersistentControl.from(field.persistentControl())
            );
        }
    }

    /** Wire form of a field-owned persistent condition. */
    public record PersistentControl(String id, String title, QueryOperator operator, List<Object> defaultValues) {
        public PersistentControl {
            defaultValues = defaultValues == null ? List.of() : List.copyOf(defaultValues);
        }

        static PersistentControl from(QueryPersistentControl control) {
            return control == null ? null : new PersistentControl(control.id(), control.title(), control.operator(),
                    control.defaultValues());
        }
    }

    /**
     * Source-neutral declaration that this query value is a persisted reference id and can be
     * selected through the source field's standard reference-resolve contract.
     */
    public record Reference(String targetModuleAlias,
                            ReferenceCardinality cardinality,
                            String labelField) {
        public Reference {
            cardinality = cardinality == null ? ReferenceCardinality.ONE : cardinality;
            labelField = labelField == null || labelField.isBlank() ? null : labelField.trim();
        }

        static Reference from(ReferencePlan plan, QueryReference declaredReference) {
            if (plan != null) {
                return new Reference(plan.target().qualifiedName(), plan.cardinality(), plan.targetLabelField());
            }
            return declaredReference == null ? null : new Reference(declaredReference.targetModuleAlias(),
                    declaredReference.cardinality(), declaredReference.labelField());
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
