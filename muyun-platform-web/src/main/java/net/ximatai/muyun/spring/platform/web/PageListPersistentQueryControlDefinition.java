package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.query.QueryOperator;
import net.ximatai.muyun.spring.dynamic.metadata.ViewControlType;

import java.util.ArrayList;
import java.util.List;

/**
 * A user-controlled query fact rendered persistently by the standard list toolbar.
 *
 * <p>Field conditions enter the standard query criteria tree. External conditions retain their
 * module-owned resolver and are sent as external query values.</p>
 */
public sealed interface PageListPersistentQueryControlDefinition
        permits PageListFieldPersistentQueryControlDefinition, PageListExternalPersistentQueryControlDefinition {
    String id();

    String title();

    Source source();

    enum Source {
        FIELD,
        EXTERNAL
    }

    /** Preserves the original external-condition DSL entry point. */
    static ExternalBuilder builder(String externalCriteriaKey) {
        return external(externalCriteriaKey);
    }

    static ExternalBuilder external(String externalCriteriaKey) {
        return new ExternalBuilder(externalCriteriaKey);
    }

    static FieldBuilder field(String id, String fieldName, QueryOperator operator) {
        return new FieldBuilder(id, fieldName, operator);
    }

    final class ExternalBuilder {
        private final String externalCriteriaKey;
        private String title;
        private ViewControlType uiType;
        private Object defaultValue;

        private ExternalBuilder(String externalCriteriaKey) {
            this.externalCriteriaKey = externalCriteriaKey;
        }

        public ExternalBuilder label(String value) {
            title = value;
            return this;
        }

        public ExternalBuilder uiType(ViewControlType value) {
            uiType = value;
            return this;
        }

        public ExternalBuilder defaultValue(Object value) {
            defaultValue = value;
            return this;
        }

        PageListExternalPersistentQueryControlDefinition build() {
            return new PageListExternalPersistentQueryControlDefinition(externalCriteriaKey, title, uiType,
                    defaultValue);
        }
    }

    final class FieldBuilder {
        private final String id;
        private final String fieldName;
        private final QueryOperator operator;
        private String title;
        private final List<Object> defaultValues = new ArrayList<>();

        private FieldBuilder(String id, String fieldName, QueryOperator operator) {
            this.id = id;
            this.fieldName = fieldName;
            this.operator = operator;
        }

        public FieldBuilder label(String value) {
            title = value;
            return this;
        }

        /** An empty value list means the control is initially visible but does not filter. */
        public FieldBuilder defaultValues(Object... values) {
            defaultValues.clear();
            if (values != null) {
                java.util.Collections.addAll(defaultValues, values);
            }
            return this;
        }

        PageListFieldPersistentQueryControlDefinition build() {
            return new PageListFieldPersistentQueryControlDefinition(id, title, fieldName, operator, defaultValues);
        }
    }
}
