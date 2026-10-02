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
        if (projection == null || ids == null || ids.isEmpty()) return Map.of();
        try {
            return readAuthorized(target, ids, projection, resolver);
        } catch (net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException denied) {
            // Optional context cannot widen access or prevent an already-authorized candidate selection.
            return Map.of();
        }
    }

    private static Map<String, String> readAuthorized(ReferenceTarget target, List<String> ids,
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
        Map<String, String> result = new LinkedHashMap<>();
        ReferenceSelectionProjectionReader.read(target, ids, List.of(projection), resolver).forEach((id, values) -> {
            Object value = values.get(projection.key());
            if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
                String text = value.toString().trim();
                if (!text.isBlank()) result.put(id, text.substring(0, Math.min(text.length(), 500)));
            }
        });
        return Map.copyOf(result);
    }

    private static ReferenceAbility<?> require(ReferenceTargetResolver resolver, ReferenceTarget target) {
        return resolver.resolve(target).orElseThrow(() -> new PlatformException("reference target is unavailable: " + target.qualifiedName()));
    }
}
