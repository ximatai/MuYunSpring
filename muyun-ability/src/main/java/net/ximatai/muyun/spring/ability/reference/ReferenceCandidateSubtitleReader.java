package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.common.exception.PlatformException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Public candidate context follows the same scoped, masked reference graph as selection projections. */
public final class ReferenceCandidateSubtitleReader {
    private ReferenceCandidateSubtitleReader() { }

    public static Map<String, String> read(ReferenceTarget target, List<String> ids,
                                            ReferenceSelectionProjection projection, ReferenceTargetResolver resolver) {
        Map<String, String> result = new LinkedHashMap<>();
        readResolved(target, ids, projection, resolver).forEach((id, value) -> result.put(id, label(value.value())));
        return Map.copyOf(result);
    }

    public static Map<String, ReferenceLoadReader.ResolvedValue> readResolved(ReferenceTarget target, List<String> ids,
            ReferenceSelectionProjection projection, ReferenceTargetResolver resolver) {
        if (projection == null || ids == null || ids.isEmpty()) return Map.of();
        Map<String, ReferenceLoadReader.ResolvedValue> result;
        try {
            result = readAuthorized(target, ids, projection, resolver);
        } catch (net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException denied) {
            // Optional context cannot widen access or prevent an already-authorized candidate selection.
            result = Map.of();
        }
        net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.referenceTextReadListener().onCandidateSubtitles(
                new ReferenceTextReadListener.CandidateSubtitles(target, projection.key(), ids, result));
        return result;
    }

    private static Map<String, ReferenceLoadReader.ResolvedValue> readAuthorized(ReferenceTarget target, List<String> ids,
                                                     ReferenceSelectionProjection projection, ReferenceTargetResolver resolver) {
        ReferenceTarget current = target;
        for (int index = 0; index < projection.path().size(); index++) {
            String field = projection.path().get(index);
            ReferenceAbility<?> ability = require(resolver, current);
            if (ability.isReferenceFieldProtected(field)) return Map.of();
            if (index < projection.path().size() - 1) {
                ReferenceTarget source = current;
                ReferencePlan hop = resolver.referencePlan(source, field).orElseThrow(() ->
                        new PlatformException("candidate subtitle hop is not a declared reference: " + source.qualifiedName() + "." + field));
                if (hop.cardinality() != ReferenceCardinality.ONE) {
                    throw new PlatformException("candidate subtitle hop requires cardinality ONE: " + field);
                }
                current = hop.target();
            }
        }
        Map<String, ReferenceLoadReader.ResolvedValue> result = new LinkedHashMap<>();
        ReferenceSelectionProjectionReader.readResolved(target, ids, List.of(projection), resolver).forEach((id, values) -> {
            var value = values.get(projection.key());
            if (value != null && label(value.value()) != null) result.put(id, value);
        });
        return Map.copyOf(result);
    }

    public static String label(Object value) {
        if (!(value instanceof CharSequence || value instanceof Number || value instanceof Boolean)) return null;
        String text = value.toString().trim();
        if (text.isBlank()) return null;
        int end = Math.min(text.length(), 500);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) end--;
        return text.substring(0, end);
    }

    private static ReferenceAbility<?> require(ReferenceTargetResolver resolver, ReferenceTarget target) {
        return resolver.resolve(target).orElseThrow(() -> new PlatformException("reference target is unavailable: " + target.qualifiedName()));
    }
}
