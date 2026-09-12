package net.ximatai.muyun.spring.ability.query;

import net.ximatai.muyun.spring.common.option.OptionBinding;
import net.ximatai.muyun.spring.common.option.OptionFieldDefinition;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;

import java.util.EnumSet;
import java.util.Set;

public record QueryField(String fieldName,
                         String title,
                         QueryValueType valueType,
                         Set<QueryOperator> operators,
                         QueryOperator defaultOperator,
                         boolean sortable,
                         boolean quickSearch,
                         OptionBinding optionBinding,
                         OptionSelectionMode selectionMode,
                         String optionTitleField,
                         QueryReference reference,
                         QueryPersistentControl persistentControl) {
    public QueryField {
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("query field name must not be blank");
        }
        title = title == null || title.isBlank() ? fieldName : title.trim();
        valueType = valueType == null ? QueryValueType.STRING : valueType;
        operators = operators == null
                ? EnumSet.of(QueryOperator.EQ)
                : operators.isEmpty() ? Set.of() : EnumSet.copyOf(operators);
        defaultOperator = defaultOperator == null && !operators.isEmpty()
                ? fallbackDefaultOperator(valueType, operators)
                : defaultOperator;
        if (defaultOperator != null && !operators.contains(defaultOperator)) {
            throw new IllegalArgumentException("default query operator must be allowed: "
                    + fieldName + "." + defaultOperator);
        }
        selectionMode = optionBinding == null ? null
                : selectionMode == null ? OptionSelectionMode.SINGLE : selectionMode;
        optionTitleField = optionBinding == null || optionTitleField == null || optionTitleField.isBlank()
                ? null : optionTitleField.trim();
        if (persistentControl != null && !operators.contains(persistentControl.operator())) {
            throw new IllegalArgumentException("persistent query operator must be allowed: "
                    + fieldName + "." + persistentControl.operator());
        }
    }

    /** Compatibility constructor for fields without a record-selection contract. */
    public QueryField(String fieldName,
                      String title,
                      QueryValueType valueType,
                      Set<QueryOperator> operators,
                      QueryOperator defaultOperator,
                      boolean sortable,
                      boolean quickSearch,
                      OptionBinding optionBinding,
                      OptionSelectionMode selectionMode,
                      String optionTitleField) {
        this(fieldName, title, valueType, operators, defaultOperator, sortable, quickSearch, optionBinding,
                selectionMode, optionTitleField, null, null);
    }

    /** Compatibility constructor for fields with a record-selection contract but no persistent control. */
    public QueryField(String fieldName, String title, QueryValueType valueType, Set<QueryOperator> operators,
                      QueryOperator defaultOperator, boolean sortable, boolean quickSearch,
                      OptionBinding optionBinding, OptionSelectionMode selectionMode, String optionTitleField,
                      QueryReference reference) {
        this(fieldName, title, valueType, operators, defaultOperator, sortable, quickSearch, optionBinding,
                selectionMode, optionTitleField, reference, null);
    }

    public static QueryField of(String fieldName, QueryOperator first, QueryOperator... rest) {
        return of(fieldName, QueryValueType.STRING, first, rest);
    }

    public static QueryField of(String fieldName, QueryValueType valueType, QueryOperator first,
                                QueryOperator... rest) {
        EnumSet<QueryOperator> operators = EnumSet.of(first, rest);
        return new QueryField(fieldName, null, valueType, operators, null, false, false, null, null, null,
                null, null);
    }

    public QueryField withTitle(String title) {
        return new QueryField(fieldName, title, valueType, operators, defaultOperator, sortable, quickSearch,
                optionBinding, selectionMode, optionTitleField, reference, persistentControl);
    }

    public QueryField withDefaultOperator(QueryOperator operator) {
        return new QueryField(fieldName, title, valueType, operators, operator, sortable, quickSearch,
                optionBinding, selectionMode, optionTitleField, reference, persistentControl);
    }

    public QueryField withSortable() {
        return new QueryField(fieldName, title, valueType, operators, defaultOperator, true, quickSearch,
                optionBinding, selectionMode, optionTitleField, reference, persistentControl);
    }

    public QueryField withQuickSearch() {
        return new QueryField(fieldName, title, valueType, operators, defaultOperator, sortable, true,
                optionBinding, selectionMode, optionTitleField, reference, persistentControl);
    }

    public QueryField withOptionBinding(OptionBinding binding) {
        return withOptionBinding(binding, OptionSelectionMode.SINGLE);
    }

    public QueryField withOptionBinding(OptionBinding binding, OptionSelectionMode selectionMode) {
        return new QueryField(fieldName, title, valueType, operators, defaultOperator, sortable, quickSearch,
                binding, selectionMode, null, reference, persistentControl);
    }

    public QueryField withOptionField(OptionFieldDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("option field definition must not be null");
        }
        return new QueryField(fieldName, title, valueType, operators, defaultOperator, sortable, quickSearch,
                definition.binding(), definition.selectionMode(), null, reference, persistentControl);
    }

    public QueryField withOptionTitleField(String optionTitleField) {
        return new QueryField(fieldName, title, valueType, operators, defaultOperator, sortable, quickSearch,
                optionBinding, selectionMode, optionTitleField, reference, persistentControl);
    }

    public QueryField withReference(QueryReference reference) {
        return new QueryField(fieldName, title, valueType, operators, defaultOperator, sortable, quickSearch,
                optionBinding, selectionMode, optionTitleField, reference, persistentControl);
    }

    /** Makes this field a standard always-visible list condition. */
    public QueryField withPersistentControl(QueryOperator operator) {
        return withPersistentControl(QueryPersistentControl.of(fieldName, title, operator));
    }

    public QueryField withPersistentControl(QueryPersistentControl control) {
        return new QueryField(fieldName, title, valueType, operators, defaultOperator, sortable, quickSearch,
                optionBinding, selectionMode, optionTitleField, reference, control);
    }

    private static QueryOperator fallbackDefaultOperator(QueryValueType valueType, Set<QueryOperator> operators) {
        QueryOperator typeDefault = valueType.defaultOperator();
        return operators.contains(typeDefault) ? typeDefault : operators.iterator().next();
    }
}
