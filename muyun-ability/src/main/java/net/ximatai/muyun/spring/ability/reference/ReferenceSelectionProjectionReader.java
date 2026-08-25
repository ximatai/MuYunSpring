package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/**
 * Batch reader for explicitly authorised picker selection projections.
 *
 * <p>A projection is relative to the selected reference target.  Direct fields and dot paths
 * deliberately share this reader so static, dynamic and mixed target chains execute through
 * {@link ReferenceAbility#projections(java.util.Collection, java.util.Collection)}.  That is
 * the reference-read boundary that owns REFERENCE data scope and field protection.</p>
 */
public final class ReferenceSelectionProjectionReader {
    private ReferenceSelectionProjectionReader() {
    }

    public static Map<String, Map<String, Object>> read(ReferenceTarget sourceTarget,
                                                         List<String> sourceIds,
                                                         List<ReferenceSelectionProjection> projections,
                                                         ReferenceTargetResolver resolver) {
        if (sourceTarget == null || sourceIds == null || sourceIds.isEmpty()
                || projections == null || projections.isEmpty()) {
            return Map.of();
        }
        if (resolver == null) {
            throw new IllegalArgumentException("reference target resolver must not be null");
        }
        List<String> ids = sourceIds.stream().filter(java.util.Objects::nonNull).map(String::valueOf)
                .map(String::trim).filter(id -> !id.isBlank()).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (ReferenceSelectionProjection projection : projections.stream()
                .filter(java.util.Objects::nonNull).distinct().toList()) {
            Map<String, Object> values = readOne(sourceTarget, ids, projection, resolver);
            values.forEach((id, value) -> result.computeIfAbsent(id, ignored -> new LinkedHashMap<>())
                    .put(projection.key(), value));
        }
        Map<String, Map<String, Object>> ordered = new LinkedHashMap<>();
        for (String id : ids) {
            Map<String, Object> values = result.get(id);
            if (values != null) {
                ordered.put(id, Collections.unmodifiableMap(new LinkedHashMap<>(values)));
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }

    private static Map<String, Object> readOne(ReferenceTarget sourceTarget,
                                               List<String> sourceIds,
                                               ReferenceSelectionProjection projection,
                                               ReferenceTargetResolver resolver) {
        if (projection.path().size() == 1) {
            PlatformAbilityRuntime.referenceReadObserver().onProjection(
                    new ReferenceReadObserver.ProjectionRequest(sourceTarget, List.of(projection.targetField()),
                            sourceIds.size(), ReferenceReadObserver.Kind.DIRECT, null, projection.key(), 0));
            Map<String, Map<String, Object>> values = require(resolver, sourceTarget)
                    .projections(sourceIds, List.of(projection.targetField()));
            Map<String, Object> result = new LinkedHashMap<>();
            values.forEach((id, fields) -> result.put(id, fields.get(projection.targetField())));
            return result;
        }
        ReferenceLoadPath path = path(sourceTarget, projection, resolver);
        return ReferenceLoadReader.readAll(path, sourceIds,
                target -> require(resolver, target), PlatformAbilityRuntime.referenceReadObserver());
    }

    private static ReferenceLoadPath path(ReferenceTarget sourceTarget,
                                          ReferenceSelectionProjection projection,
                                          ReferenceTargetResolver resolver) {
        ReferenceTarget current = sourceTarget;
        List<ReferenceLoadPath.Hop> hops = new java.util.ArrayList<>();
        for (String viaField : projection.path().subList(0, projection.path().size() - 1)) {
            ReferenceTarget hopSource = current;
            ReferencePlan hop = resolver.referencePlan(hopSource, viaField)
                    .orElseThrow(() -> new PlatformException("selection projection hop is not a declared reference: "
                            + hopSource.qualifiedName() + "." + viaField));
            if (hop.cardinality() != ReferenceCardinality.ONE) {
                throw new PlatformException("selection projection hop requires cardinality ONE: "
                        + hopSource.qualifiedName() + "." + viaField);
            }
            hops.add(new ReferenceLoadPath.Hop(hop.target(), viaField));
            current = hop.target();
        }
        return new ReferenceLoadPath("selection", sourceTarget, hops, projection.targetField(), projection.key());
    }

    private static ReferenceAbility<?> require(ReferenceTargetResolver resolver, ReferenceTarget target) {
        return resolver.resolve(target).orElseThrow(() -> new PlatformException(
                "reference target is unavailable: " + target.qualifiedName()));
    }
}
