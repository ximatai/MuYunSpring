package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Runtime reference contract. Source values and every reference-graph edge always contain target
 * record ids. {@code targetKeyField} is only an alternate candidate/import match field; it never
 * changes the persisted identity.
 */
public record ReferencePlan(
        String sourceField,
        ReferenceTarget target,
        ReferenceCardinality cardinality,
        List<ReferenceProjection> projections,
        ReferenceIntegrityPolicy integrity,
        ReferenceTenantScope tenantScope,
        List<ReferenceCandidateDependency> candidateDependencies,
        List<ReferenceSelectionProjection> selectionProjections,
        String targetKeyField,
        String targetLabelField
) {
    public ReferencePlan {
        if (sourceField == null || sourceField.isBlank()) {
            throw new PlatformException("reference sourceField must not be blank");
        }
        if (target == null) {
            throw new PlatformException("reference target must not be null");
        }
        if (cardinality == null) {
            cardinality = ReferenceCardinality.ONE;
        }
        projections = projections == null ? List.of() : List.copyOf(projections);
        integrity = integrity == null ? ReferenceIntegrityPolicy.DEFAULT : integrity;
        tenantScope = tenantScope == null ? ReferenceTenantScope.SAME_TENANT : tenantScope;
        candidateDependencies = candidateDependencies == null ? List.of() : List.copyOf(candidateDependencies);
        selectionProjections = selectionProjections == null ? List.of() : selectionProjections.stream()
                .filter(java.util.Objects::nonNull).distinct().toList();
        targetKeyField = normalizeField(targetKeyField, "id");
        targetLabelField = normalizeField(targetLabelField, null);
        if (cardinality == ReferenceCardinality.MANY
                && integrity.onTargetUnavailable() == ReferenceTargetUnavailablePolicy.RESTRICT) {
            throw new PlatformException("RESTRICT reference deletion requires cardinality ONE: " + sourceField);
        }
        if (cardinality == ReferenceCardinality.MANY
                && integrity.onTargetUnavailable() == ReferenceTargetUnavailablePolicy.CASCADE_DELETE) {
            throw new PlatformException("CASCADE_DELETE reference deletion requires cardinality ONE: " + sourceField);
        }
        validateOutputFields(sourceField, projections);
    }

    public ReferencePlan(String sourceField,
                         ReferenceTarget target,
                         ReferenceCardinality cardinality) {
        this(sourceField, target, cardinality, List.of(), ReferenceIntegrityPolicy.DEFAULT,
                ReferenceTenantScope.SAME_TENANT, List.of(), List.of(), "id", null);
    }

    public ReferencePlan(String sourceField,
                         ReferenceTarget target,
                         ReferenceCardinality cardinality,
                         List<ReferenceProjection> projections,
                         ReferenceIntegrityPolicy integrity) {
        this(sourceField, target, cardinality, projections, integrity, ReferenceTenantScope.SAME_TENANT, List.of(), List.of(), "id", null);
    }

    public ReferencePlan(String sourceField, ReferenceTarget target, ReferenceCardinality cardinality,
                         List<ReferenceProjection> projections, ReferenceIntegrityPolicy integrity,
                         ReferenceTenantScope tenantScope) {
        this(sourceField, target, cardinality, projections, integrity, tenantScope, List.of(), List.of(), "id", null);
    }

    public static ReferencePlan of(String sourceField, ReferenceTarget target, ReferenceCardinality cardinality) {
        return new ReferencePlan(sourceField, target, cardinality, List.of(), ReferenceIntegrityPolicy.DEFAULT,
                ReferenceTenantScope.SAME_TENANT, List.of(), List.of(), "id", null);
    }

    public ReferencePlan withProjection(String targetField, String outputField) {
        return new ReferencePlan(sourceField(), target, cardinality,
                appendProjection(new ReferenceProjection(targetField, outputField)), integrity, tenantScope,
                candidateDependencies, selectionProjections, targetKeyField, targetLabelField);
    }

    /** Compatibility constructor for callers created before picker selection projections existed. */
    public ReferencePlan(String sourceField, ReferenceTarget target, ReferenceCardinality cardinality,
                         List<ReferenceProjection> projections, ReferenceIntegrityPolicy integrity,
                         ReferenceTenantScope tenantScope, List<ReferenceCandidateDependency> candidateDependencies) {
        this(sourceField, target, cardinality, projections, integrity, tenantScope, candidateDependencies, List.of(), "id", null);
    }

    /** Compatibility constructor for plans declared before target key/label selection was configurable. */
    public ReferencePlan(String sourceField, ReferenceTarget target, ReferenceCardinality cardinality,
                         List<ReferenceProjection> projections, ReferenceIntegrityPolicy integrity,
                         ReferenceTenantScope tenantScope, List<ReferenceCandidateDependency> candidateDependencies,
                         List<ReferenceSelectionProjection> selectionProjections) {
        this(sourceField, target, cardinality, projections, integrity, tenantScope, candidateDependencies,
                selectionProjections, "id", null);
    }

    /** Configures candidate matching and rendering without changing the persisted record id. */
    public ReferencePlan withTargetFields(String targetKeyField, String targetLabelField) {
        return new ReferencePlan(sourceField, target, cardinality, projections, integrity, tenantScope,
                candidateDependencies, selectionProjections, targetKeyField, targetLabelField);
    }

    /** True when existing id/title reference facades remain sufficient. */
    public boolean usesDefaultTargetFields() {
        return "id".equals(targetKeyField) && targetLabelField == null;
    }

    private static String normalizeField(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    public List<String> normalizeValues(Object value) {
        if (value == null) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (cardinality == ReferenceCardinality.MANY) {
            appendMany(values, value);
        } else {
            appendOne(values, value);
        }
        return List.copyOf(values);
    }

    private void appendMany(LinkedHashSet<String> values, Object value) {
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> appendOne(values, item));
            return;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                appendOne(values, Array.get(value, i));
            }
            return;
        }
        if (value instanceof String text) {
            for (String item : text.split(",")) {
                appendOne(values, item);
            }
            return;
        }
        appendOne(values, value);
    }

    private void appendOne(LinkedHashSet<String> values, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (!text.isBlank()) {
            values.add(text);
        }
    }

    private List<ReferenceProjection> appendProjection(ReferenceProjection projection) {
        LinkedHashSet<ReferenceProjection> next = new LinkedHashSet<>(projections);
        next.add(projection);
        return List.copyOf(next);
    }

    private static void validateOutputFields(String sourceField, List<ReferenceProjection> projections) {
        LinkedHashSet<String> outputFields = new LinkedHashSet<>();
        for (ReferenceProjection projection : projections) {
            if (!outputFields.add(projection.outputField())) {
                throw new PlatformException("duplicate reference outputField: "
                        + sourceField + "." + projection.outputField());
            }
        }
    }
}
