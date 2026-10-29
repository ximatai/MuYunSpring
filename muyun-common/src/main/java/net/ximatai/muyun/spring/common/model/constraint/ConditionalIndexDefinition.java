package net.ximatai.muyun.spring.common.model.constraint;

import net.ximatai.muyun.database.core.builder.sql.SchemaBuildRules;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;

/** Source-neutral conditional index declaration. Names are stable schema migration identities. */
public record ConditionalIndexDefinition(String name, List<String> fields, boolean unique,
                                         List<IndexCondition> conditions, String message) {
    public ConditionalIndexDefinition {
        if (!SchemaBuildRules.isSafeIdentifier(name) || name.length() > 63) {
            throw new IllegalArgumentException("conditional index requires a stable identifier of at most 63 characters");
        }
        fields = fields == null ? List.of() : List.copyOf(fields);
        if (fields.isEmpty() || fields.stream().distinct().count() != fields.size()
                || fields.stream().anyMatch(field -> !SchemaBuildRules.isSafeIdentifier(field))) {
            throw new IllegalArgumentException("conditional index requires distinct physical fields");
        }
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        if (conditions.isEmpty()) throw new IllegalArgumentException("conditional index requires conditions");
        if (conditions.stream().map(IndexCondition::field).distinct().count() != conditions.size()) {
            throw new IllegalArgumentException("conditional index conditions must use distinct fields");
        }
        conditions = conditions.stream().sorted(java.util.Comparator.comparing(IndexCondition::field)).toList();
        message = message == null ? "" : message;
    }

    public ConditionalIndexDefinition(String name, List<String> fields, boolean unique, List<IndexCondition> conditions) {
        this(name, fields, unique, conditions, "");
    }

    public TenantUniqueConstraintDefinition uniqueConstraint() {
        if (!unique) throw new IllegalStateException("index is not unique");
        return new TenantUniqueConstraintDefinition(fields, message, name, conditions);
    }

    public static List<ConditionalIndexDefinition> normalize(List<ConditionalIndexDefinition> definitions) {
        var result = definitions == null ? List.<ConditionalIndexDefinition>of() : List.copyOf(definitions);
        var names = new HashSet<String>();
        var uniqueIdentities = new HashSet<TenantUniqueConstraintDefinition.Identity>();
        for (var definition : result) {
            if (!names.add(definition.name())) throw new IllegalArgumentException("duplicate conditional index name: " + definition.name());
            if (definition.unique() && !uniqueIdentities.add(definition.uniqueConstraint().identity())) {
                throw new IllegalArgumentException("duplicate conditional unique definition: " + definition.name());
            }
        }
        return result;
    }

    public static List<ConditionalIndexDefinition> from(Class<?> model) {
        if (model == null) return List.of();
        var result = Arrays.stream(model.getAnnotationsByType(ConditionalIndex.class)).map(index ->
                new ConditionalIndexDefinition(index.name(), List.of(index.fields()), index.unique(),
                        Arrays.stream(index.when()).map(condition -> new IndexCondition(condition.field(),
                                condition.operator(), List.of(condition.values()))).toList(), index.message())).toList();
        return normalize(result);
    }
}
