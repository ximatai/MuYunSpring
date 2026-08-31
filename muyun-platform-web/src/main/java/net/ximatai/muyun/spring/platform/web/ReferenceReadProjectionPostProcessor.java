package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoadPath;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoadReader;
import net.ximatai.muyun.spring.ability.reference.ReferenceReadObserver;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceProjection;
import net.ximatai.muyun.spring.ability.reference.ReferenceSummaryPlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;

import java.util.Collections;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Enriches static list output through the source-independent reference contract.
 *
 * <p>SQL joins remain an optimization for static-to-static projections. This
 * post-processor is the semantic fallback for every target that can be resolved
 * through {@link net.ximatai.muyun.spring.ability.reference.ReferenceTargetResolver},
 * including dynamic entities.</p>
 */
final class ReferenceReadProjectionPostProcessor {
    private ReferenceReadProjectionPostProcessor() {
    }

    static List<Map<String, Object>> apply(Class<?> modelClass, List<Map<String, Object>> records) {
        return apply(modelClass, records, null);
    }

    static List<Map<String, Object>> apply(Class<?> modelClass,
                                            List<Map<String, Object>> records,
                                            Collection<String> outputFields) {
        if (records == null || records.isEmpty()) {
            return records == null ? List.of() : records;
        }
        if (modelClass == null) {
            return records.stream()
                    .map(record -> restrictOutput(record, outputFields))
                    .map(Collections::unmodifiableMap)
                    .toList();
        }
        List<ReferencePlan> plans = StaticReferenceResolver.plans(modelClass).stream()
                .map(plan -> forOutputFields(plan, outputFields))
                .filter(plan -> plan != null)
                .toList();
        List<ReferenceLoadPath> loadPaths = StaticReferenceResolver.loadPaths(modelClass).stream()
                .filter(path -> outputFields == null || outputFields.contains(path.outputField()))
                .toList();
        List<ReferenceSummaryPlan> summaryPlans = StaticReferenceResolver.summaryPlans(modelClass).stream()
                .filter(summary -> outputFields == null || outputFields.contains(summary.outputField()))
                .toList();
        if (plans.isEmpty() && loadPaths.isEmpty() && summaryPlans.isEmpty()) {
            return records.stream()
                    .map(record -> restrictOutput(record, outputFields))
                    .map(Collections::unmodifiableMap)
                    .toList();
        }
        List<Map<String, Object>> output = records.stream()
                .<Map<String, Object>>map(record -> new LinkedHashMap<>(record))
                .toList();
        Map<ReferencePlan, TargetRequest> requests = collectRequests(modelClass, output, plans, summaryPlans);
        Map<ReferencePlan, TargetValues> resolved = resolve(requests);
        for (Map<String, Object> record : output) {
            for (ReferencePlan plan : plans) {
                applyPlan(record, plan, resolved.get(plan));
            }
            for (ReferenceSummaryPlan summary : summaryPlans) {
                List<String> ids = sourcePlan(modelClass, summary.sourceField())
                        .normalizeValues(record.get(summary.sourceField()));
                record.put(summary.outputField(), summaryValue(ids,
                        resolved.get(sourcePlan(modelClass, summary.sourceField())), summary));
            }
        }
        applyLoadPaths(output, modelClass, loadPaths);
        return output.stream()
                .map(record -> restrictOutput(record, outputFields))
                .map(Collections::unmodifiableMap)
                .toList();
    }

    private static ReferencePlan forOutputFields(ReferencePlan plan, Collection<String> outputFields) {
        if (outputFields == null) {
            return plan.projections().isEmpty() ? null : plan;
        }
        Set<String> fields = new LinkedHashSet<>(outputFields);
        List<ReferenceProjection> projections = plan.projections().stream()
                .filter(projection -> fields.contains(projection.outputField()))
                .toList();
        if (projections.isEmpty()) {
            return null;
        }
        return new ReferencePlan(plan.sourceField(), plan.target(), plan.cardinality(), projections, plan.integrity(),
                plan.tenantScope(), plan.candidateDependencies(), plan.selectionProjections(),
                plan.targetKeyField(), plan.targetLabelField());
    }

    private static Map<String, Object> restrictOutput(Map<String, Object> record, Collection<String> outputFields) {
        if (outputFields == null) {
            return record;
        }
        Set<String> fields = new LinkedHashSet<>();
        fields.add(StandardEntitySchema.ID_FIELD);
        fields.add(StandardEntitySchema.VERSION_FIELD);
        fields.add(StandardEntitySchema.DELETED_AT_FIELD);
        fields.addAll(outputFields);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        fields.forEach(field -> {
            if (record.containsKey(field)) {
                result.put(field, record.get(field));
            }
        });
        return result;
    }

    private static Map<ReferencePlan, TargetRequest> collectRequests(Class<?> modelClass,
                                                                         List<Map<String, Object>> records,
                                                                         List<ReferencePlan> plans,
                                                                         List<ReferenceSummaryPlan> summaries) {
        Map<ReferencePlan, TargetRequest> requests = new LinkedHashMap<>();
        for (ReferencePlan plan : plans) {
            TargetRequest request = requests.computeIfAbsent(plan, TargetRequest::new);
            plan.projections().stream().map(ReferenceProjection::targetField).forEach(request.fields::add);
            for (Map<String, Object> record : records) {
                request.ids.addAll(plan.normalizeValues(record.get(plan.sourceField())));
            }
        }
        for (ReferenceSummaryPlan summary : summaries) {
            ReferencePlan source = sourcePlan(modelClass, summary.sourceField());
            TargetRequest request = requests.computeIfAbsent(source, TargetRequest::new);
            request.fields.addAll(summary.fields().stream().filter(field -> !"id".equals(field)).toList());
            for (Map<String, Object> record : records) {
                request.ids.addAll(source.normalizeValues(record.get(summary.sourceField())));
            }
        }
        return requests;
    }

    private static ReferencePlan sourcePlan(Class<?> modelClass, String sourceField) {
        return StaticReferenceResolver.plans(modelClass).stream()
                .filter(plan -> plan.sourceField().equals(sourceField))
                .findFirst()
                .orElseThrow(() -> new PlatformException("reference summary source is unavailable: "
                        + modelClass.getName() + "." + sourceField));
    }

    private static Object summaryValue(List<String> ids, TargetValues target, ReferenceSummaryPlan summary) {
        if (summary.cardinality() == ReferenceCardinality.MANY) {
            return ids.stream().filter(id -> target != null && target.projections.containsKey(id))
                    .map(id -> summaryItem(id, target.projections.get(id), summary.fields())).toList();
        }
        if (ids.isEmpty() || target == null || !target.projections.containsKey(ids.getFirst())) {
            return null;
        }
        return summaryItem(ids.getFirst(), target.projections.get(ids.getFirst()), summary.fields());
    }

    private static Map<String, Object> summaryItem(String id, Map<String, Object> values, List<String> fields) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        for (String field : fields) {
            if (!"id".equals(field)) {
                item.put(field, values == null ? null : values.get(field));
            }
        }
        return Collections.unmodifiableMap(item);
    }

    private static Map<ReferencePlan, TargetValues> resolve(Map<ReferencePlan, TargetRequest> requests) {
        Map<ReferencePlan, TargetValues> resolved = new LinkedHashMap<>();
        for (Map.Entry<ReferencePlan, TargetRequest> entry : requests.entrySet()) {
            TargetRequest request = entry.getValue();
            if (request.ids.isEmpty()) {
                resolved.put(entry.getKey(), TargetValues.EMPTY);
                continue;
            }
            List<String> ids = List.copyOf(request.ids);
            List<String> fields = List.copyOf(request.fields);
            Map<String, Map<String, Object>> projections = request.fields.isEmpty()
                    // An id-only summary is a projection of the source reference itself. It
                    // deliberately needs no target read, but must retain every normalized id.
                    ? ids.stream().collect(java.util.stream.Collectors.toMap(
                            id -> id, ignored -> Map.of(), (first, ignored) -> first, LinkedHashMap::new))
                    : readTargetProjection(entry.getKey(), ids, fields);
            resolved.put(entry.getKey(), new TargetValues(projections));
        }
        return resolved;
    }

    private static ReferenceAbility<?> resolveTarget(ReferenceTarget target) {
        return PlatformAbilityRuntime.referenceTargetResolver().resolve(target)
                .orElseThrow(() -> new PlatformException("reference target is not registered: "
                        + target.qualifiedName()));
    }

    private static Map<String, Map<String, Object>> readTargetProjection(ReferencePlan plan,
                                                                           List<String> ids,
                                                                           List<String> fields) {
        PlatformAbilityRuntime.referenceReadObserver().onProjection(
                new ReferenceReadObserver.ProjectionRequest(plan.target(), fields, ids.size(),
                        ReferenceReadObserver.Kind.DIRECT, null, null, 0));
        ReferenceAbility<?> target = resolveTarget(plan.target());
        Map<String, Map<String, Object>> projections = target.projections(plan, ids, fields);
        // Keep existing target adapters compatible for the unchanged id/title contract. New
        // key-aware plans must always use the plan-aware capability method.
        if ((projections != null && !projections.isEmpty()) || !plan.usesDefaultTargetFields()) {
            return projections;
        }
        return target.projections(ids, fields);
    }

    private static void applyPlan(Map<String, Object> record, ReferencePlan plan, TargetValues target) {
        List<String> ids = plan.normalizeValues(record.get(plan.sourceField()));
        for (ReferenceProjection projection : plan.projections()) {
            record.put(projection.outputField(), projectionValue(ids, target.projections,
                    projection.targetField(), plan.cardinality()));
        }
    }

    private static Object projectionValue(List<String> ids,
                                          Map<String, Map<String, Object>> values,
                                          String field,
                                          ReferenceCardinality cardinality) {
        if (cardinality == ReferenceCardinality.MANY) {
            return ids.stream().map(id -> value(values, id, field)).filter(java.util.Objects::nonNull).toList();
        }
        return ids.isEmpty() ? null : value(values, ids.getFirst(), field);
    }

    private static Object value(Map<String, Map<String, Object>> values, String id, String field) {
        Map<String, Object> fields = values.get(id);
        return fields == null ? null : fields.get(field);
    }

    private static void applyLoadPaths(List<Map<String, Object>> records,
                                       Class<?> modelClass,
                                       List<ReferenceLoadPath> paths) {
        if (paths.isEmpty()) {
            return;
        }
        Map<String, ReferencePlan> sourcePlans = StaticReferenceResolver.plans(modelClass).stream()
                .collect(java.util.stream.Collectors.toMap(ReferencePlan::sourceField, plan -> plan,
                        (first, ignored) -> first));
        for (ReferenceLoadPath path : paths) {
            ReferencePlan source = sourcePlans.get(path.sourceField());
            if (source == null) {
                throw new PlatformException("ReferenceLoad source is unavailable: "
                        + modelClass.getName() + "." + path.sourceField());
            }
            List<String> sourceValues = records.stream()
                    .flatMap(record -> source.normalizeValues(record.get(path.sourceField())).stream())
                    .distinct()
                    .toList();
            Map<String, Object> values = ReferenceLoadReader.readAll(source, path, sourceValues,
                    target -> PlatformAbilityRuntime.referenceTargetResolver().resolve(target).orElseThrow(
                            () -> new PlatformException("reference target is not registered: " + target.qualifiedName())),
                    PlatformAbilityRuntime.referenceReadObserver());
            for (Map<String, Object> record : records) {
                List<String> sourceValuesForRecord = source.normalizeValues(record.get(path.sourceField()));
                record.put(path.outputField(), sourceValuesForRecord.isEmpty() ? null : values.get(sourceValuesForRecord.getFirst()));
            }
        }
    }

    private static final class TargetRequest {
        private TargetRequest(ReferencePlan plan) {
        }
        private final Set<String> ids = new LinkedHashSet<>();
        private final Set<String> fields = new LinkedHashSet<>();
    }

    private record TargetValues(Map<String, Map<String, Object>> projections) {
        private static final TargetValues EMPTY = new TargetValues(Map.of());
    }
}
