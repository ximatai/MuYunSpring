package net.ximatai.muyun.spring.ability.reference;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Source-independent batch executor for declared scalar reference read facts.
 *
 * <p>The caller supplies record access because static entities and dynamic records
 * store their values differently.  The execution contract is deliberately kept in
 * the ability layer: HTTP list projection and domain read facades must share the
 * same reference values without depending on each other.</p>
 */
public final class ReferenceReadPipeline<T> {
    private final List<ReferencePlan> plans;
    private final List<ReferenceLoadPath> paths;
    private final Function<T, Map<String, Object>> values;
    private final BiConsumer<T, Map<String, Object>> writes;
    private final Function<ReferenceTarget, ReferenceAbility<?>> abilities;
    private final ReferenceReadObserver observer;

    public ReferenceReadPipeline(List<ReferencePlan> plans,
                                 List<ReferenceLoadPath> paths,
                                 Function<T, Map<String, Object>> values,
                                 BiConsumer<T, Map<String, Object>> writes,
                                 Function<ReferenceTarget, ReferenceAbility<?>> abilities) {
        this(plans, paths, values, writes, abilities, ReferenceReadObserver.NONE);
    }

    public ReferenceReadPipeline(List<ReferencePlan> plans,
                                 List<ReferenceLoadPath> paths,
                                 Function<T, Map<String, Object>> values,
                                 BiConsumer<T, Map<String, Object>> writes,
                                 Function<ReferenceTarget, ReferenceAbility<?>> abilities,
                                 ReferenceReadObserver observer) {
        this.plans = plans == null ? List.of() : List.copyOf(plans);
        this.paths = paths == null ? List.of() : List.copyOf(paths);
        this.values = values;
        this.writes = writes;
        this.abilities = abilities;
        this.observer = observer == null ? ReferenceReadObserver.NONE : observer;
    }

    public void populate(Collection<T> sourceRecords) {
        List<T> records = sourceRecords == null ? List.of() : sourceRecords.stream().filter(java.util.Objects::nonNull).toList();
        if (records.isEmpty()) return;
        populateDirect(records);
        populatePaths(records);
    }

    private void populateDirect(List<T> records) {
        Map<ReferenceTarget, TargetRequest> requests = new LinkedHashMap<>();
        for (ReferencePlan plan : plans) {
            if (plan.projections().isEmpty()) continue;
            TargetRequest request = requests.computeIfAbsent(plan.target(), ignored -> new TargetRequest(plan));
            plan.projections().stream().map(ReferenceProjection::targetField).forEach(request.fields::add);
            for (T record : records) request.ids.addAll(ids(plan, record));
        }
        Map<ReferenceTarget, Map<String, Map<String, Object>>> resolved = new LinkedHashMap<>();
        requests.forEach((key, request) -> {
            ReferencePlan plan = request.plan;
            if (request.ids.isEmpty()) {
                resolved.put(key, Map.of());
                return;
            }
            List<String> ids = List.copyOf(request.ids);
            List<String> fields = List.copyOf(request.fields);
            observer.onProjection(new ReferenceReadObserver.ProjectionRequest(plan.target(), fields, ids.size(),
                    ReferenceReadObserver.Kind.DIRECT, null, null, 0));
            resolved.put(key, require(plan.target()).projections(ids, fields));
        });
        for (T record : records) {
            Map<String, Object> output = new LinkedHashMap<>();
            for (ReferencePlan plan : plans) {
                if (plan.projections().isEmpty()) continue;
                List<String> ids = ids(plan, record);
                for (ReferenceProjection projection : plan.projections()) {
                    Object projected = plan.cardinality() == ReferenceCardinality.MANY
                            ? ids.stream().map(id -> resolved.get(plan.target()).getOrDefault(id, Map.of()).get(projection.targetField()))
                            .filter(java.util.Objects::nonNull).toList()
                            : ids.isEmpty() ? null : resolved.get(plan.target()).getOrDefault(ids.getFirst(), Map.of()).get(projection.targetField());
                    output.put(projection.outputField(), projected);
                }
            }
            if (!output.isEmpty()) writes.accept(record, output);
        }
    }

    private void populatePaths(List<T> records) {
        Map<String, ReferencePlan> sources = plans.stream().collect(java.util.stream.Collectors.toMap(
                ReferencePlan::sourceField, Function.identity(), (left, ignored) -> left));
        for (ReferenceLoadPath path : paths) {
            ReferencePlan source = sources.get(path.sourceField());
            if (source == null) throw new IllegalArgumentException("ReferenceLoad source is unavailable: " + path.sourceField());
            Map<T, List<String>> idsByRecord = new LinkedHashMap<>();
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (T record : records) {
                List<String> sourceIds = ids(source, record);
                idsByRecord.put(record, sourceIds);
                ids.addAll(sourceIds);
            }
            Map<String, Object> loaded = ReferenceLoadReader.readAll(path, List.copyOf(ids), this::require, observer);
            for (Map.Entry<T, List<String>> entry : idsByRecord.entrySet()) {
                List<String> sourceIds = entry.getValue();
                Object value = source.cardinality() == ReferenceCardinality.MANY && path.hops().isEmpty()
                        ? sourceIds.stream().map(loaded::get).filter(java.util.Objects::nonNull).toList()
                        : sourceIds.isEmpty() ? null : loaded.get(sourceIds.getFirst());
                Map<String, Object> output = new LinkedHashMap<>();
                output.put(path.outputField(), value);
                writes.accept(entry.getKey(), output);
            }
        }
    }

    private Object value(T record, String field) {
        return values.apply(record).get(field);
    }

    private List<String> ids(ReferencePlan plan, T record) {
        Object source = value(record, plan.sourceField());
        if (source instanceof Collection<?> collection) {
            return collection.stream().filter(java.util.Objects::nonNull).map(String::valueOf)
                    .filter(value -> !value.isBlank()).distinct().toList();
        }
        return plan.normalizeValues(source);
    }

    private ReferenceAbility<?> require(ReferenceTarget target) {
        ReferenceAbility<?> ability = abilities.apply(target);
        if (ability == null) throw new IllegalArgumentException("Reference target is unavailable: " + target.qualifiedName());
        return ability;
    }

    private static final class TargetRequest {
        private final ReferencePlan plan;
        private final LinkedHashSet<String> ids = new LinkedHashSet<>();
        private final LinkedHashSet<String> fields = new LinkedHashSet<>();

        private TargetRequest(ReferencePlan plan) {
            this.plan = plan;
        }
    }
}
