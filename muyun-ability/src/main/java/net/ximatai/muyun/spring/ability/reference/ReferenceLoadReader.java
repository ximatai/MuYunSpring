package net.ximatai.muyun.spring.ability.reference;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Executes a fully resolved typed reference path for static and dynamic records alike. */
public final class ReferenceLoadReader {
    private ReferenceLoadReader() {
    }

    public static Object read(ReferenceLoadPath path,
                              List<String> sourceIds,
                              Function<ReferenceTarget, ReferenceAbility<?>> abilities) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return null;
        }
        if (sourceIds.size() != 1) {
            throw new IllegalArgumentException("ReferenceLoad path requires exactly one source id: "
                    + path.sourceField());
        }
        return readAll(path, sourceIds, abilities).get(sourceIds.getFirst());
    }

    /**
     * Resolves one typed path for a batch of source records. Each source id represents one
     * cardinality-ONE path; callers keep the resulting value associated with that source id.
     */
    public static Map<String, Object> readAll(ReferenceLoadPath path,
                                              List<String> sourceIds,
                                              Function<ReferenceTarget, ReferenceAbility<?>> abilities) {
        return readAll(path, sourceIds, abilities, ReferenceReadObserver.NONE);
    }

    public static Map<String, Object> readAll(ReferenceLoadPath path,
                                              List<String> sourceIds,
                                              Function<ReferenceTarget, ReferenceAbility<?>> abilities,
                                              ReferenceReadObserver observer) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return Map.of();
        }
        ReferenceReadObserver readObserver = observer == null ? ReferenceReadObserver.NONE : observer;
        Map<String, String> currentIds = new LinkedHashMap<>();
        sourceIds.stream().filter(Objects::nonNull).filter(id -> !id.isBlank())
                .forEach(id -> currentIds.putIfAbsent(id, id));
        ReferenceTarget current = path.sourceTarget();
        for (int hopIndex = 0; hopIndex < path.hops().size(); hopIndex++) {
            ReferenceLoadPath.Hop hop = path.hops().get(hopIndex);
            if (hop.viaField() == null) {
                throw new IllegalArgumentException("ReferenceLoad path hop must resolve via field: " + current);
            }
            List<String> ids = currentIds.values().stream().distinct().toList();
            readObserver.onProjection(new ReferenceReadObserver.ProjectionRequest(current, List.of(hop.viaField()),
                    ids.size(), ReferenceReadObserver.Kind.PATH, path.sourceField(), path.outputField(), hopIndex));
            Map<String, Map<String, Object>> values = require(abilities, current).projections(
                    ids, List.of(hop.viaField()));
            currentIds.replaceAll((sourceId, currentId) -> nextId(values, currentId, hop.viaField()));
            currentIds.entrySet().removeIf(entry -> entry.getValue() == null);
            if (currentIds.isEmpty()) {
                return Map.of();
            }
            current = hop.target();
        }
        List<String> terminalIds = currentIds.values().stream().distinct().toList();
        readObserver.onProjection(new ReferenceReadObserver.ProjectionRequest(current, List.of(path.terminalField()),
                terminalIds.size(), ReferenceReadObserver.Kind.PATH, path.sourceField(), path.outputField(),
                path.hops().size()));
        Map<String, Map<String, Object>> values = require(abilities, current).projections(
                terminalIds, List.of(path.terminalField()));
        Map<String, Object> result = new LinkedHashMap<>();
        currentIds.forEach((sourceId, terminalId) -> result.put(sourceId,
                values.getOrDefault(terminalId, Map.of()).get(path.terminalField())));
        return result;
    }

    /**
     * Resolves the first path target through the source reference's configured candidate key
     * before executing the id-based path.  Subsequent hops deliberately remain id-backed; a
     * configured non-id hop is rejected by {@link ReferenceSelectionProjectionReader} until the
     * path format can carry each hop's plan without ambiguity.
     */
    public static Map<String, Object> readAll(ReferencePlan sourcePlan,
                                              ReferenceLoadPath path,
                                              List<String> sourceValues,
                                              Function<ReferenceTarget, ReferenceAbility<?>> abilities,
                                              ReferenceReadObserver observer) {
        if (sourcePlan == null || sourcePlan.usesDefaultTargetFields()) {
            return readAll(path, sourceValues, abilities, observer);
        }
        if (!sourcePlan.target().equals(path.sourceTarget())) {
            throw new IllegalArgumentException("ReferenceLoad source plan target does not match path: "
                    + path.sourceField());
        }
        List<String> values = sourceValues == null ? List.of() : sourceValues.stream()
                .filter(Objects::nonNull).map(String::valueOf).map(String::trim)
                .filter(value -> !value.isBlank()).distinct().toList();
        if (values.isEmpty()) return Map.of();
        Map<String, String> recordIds = require(abilities, sourcePlan.target()).referenceRecordIds(sourcePlan, values);
        Map<String, Object> loaded = readAll(path, recordIds.values().stream().distinct().toList(), abilities, observer);
        Map<String, Object> result = new LinkedHashMap<>();
        recordIds.forEach((value, recordId) -> {
            if (loaded.containsKey(recordId)) result.put(value, loaded.get(recordId));
        });
        return result;
    }

    private static String nextId(Map<String, Map<String, Object>> values, String currentId, String viaField) {
        Object value = values.getOrDefault(currentId, Map.of()).get(viaField);
        if (value == null) {
            return null;
        }
        String id = String.valueOf(value);
        return id.isBlank() ? null : id;
    }

    private static ReferenceAbility<?> require(Function<ReferenceTarget, ReferenceAbility<?>> abilities,
                                               ReferenceTarget target) {
        ReferenceAbility<?> ability = abilities.apply(target);
        if (ability == null) {
            throw new IllegalArgumentException("ReferenceLoad target is unavailable: " + target.qualifiedName());
        }
        return ability;
    }
}
