package net.ximatai.muyun.spring.ability.reference;

import java.util.List;
import java.util.Objects;

/**
 * Receives text facts produced by an authorized reference read, without changing its raw result.
 * Presentation adapters may capture these facts; formulas, effects and persisted fields remain raw.
 * Unlike the payload-free metrics observer, this internal collaborator receives business text.
 */
@FunctionalInterface
public interface ReferenceTextReadListener {
    ReferenceTextReadListener NONE = projection -> { };

    void onProjection(Projection projection);

    default void onCandidateSubtitles(CandidateSubtitles subtitles) { }

    /** Captures lifecycle identity from the authorized read itself, never by re-reading a target. */
    default void onRecords(net.ximatai.muyun.spring.ability.CrudAbility<?> owner, java.util.Collection<?> records) { }

    /** Explicit platform projection/copy; equal IDs or equal business values alone never bind owners. */
    default void onOwnerCopy(Object source, Object output) { }

    record CandidateSubtitles(ReferenceTarget target, String projection, List<String> candidateIds,
                             java.util.Map<String, ReferenceLoadReader.ResolvedValue> sources) {
        public CandidateSubtitles {
            Objects.requireNonNull(target); Objects.requireNonNull(projection);
            candidateIds = candidateIds.stream().filter(Objects::nonNull).map(String::trim)
                    .filter(id -> !id.isBlank()).distinct().toList();
            sources = java.util.Map.copyOf(sources);
        }
    }

    record Text(ReferenceTarget target, String recordId, String field, String originalText) {
        public Text {
            Objects.requireNonNull(target); Objects.requireNonNull(recordId);
            Objects.requireNonNull(field); Objects.requireNonNull(originalText);
        }
    }

    /** A summary item is bound to its declaring parent field and target ID, never arbitrary nested JSON. */
    record Projection(Object owner, String sourceField, List<String> sourceIds, String outputField,
                      String summaryField, String summaryItemId, boolean collection, List<Text> texts) {
        public Projection {
            Objects.requireNonNull(owner); Objects.requireNonNull(outputField);
            sourceIds = List.copyOf(sourceIds);
            if ((summaryField == null) != (summaryItemId == null))
                throw new IllegalArgumentException("Reference text summary requires its field and item identity");
            texts = List.copyOf(texts);
            if (!collection && texts.size() > 1)
                throw new IllegalArgumentException("Scalar reference text requires at most one target");
        }

        public static Projection fromResolved(Object owner, String sourceField, List<String> sourceIds,
                String outputField, boolean collection, List<ReferenceLoadReader.ResolvedValue> values) {
            var texts = new java.util.ArrayList<Text>();
            for (var value : values) {
                if (value == null || value.value() == null) continue;
                if (!(value.value() instanceof String text)) return null;
                texts.add(new Text(value.target(), value.recordId(), value.field(), text));
            }
            return texts.isEmpty() ? null : new Projection(owner, sourceField, sourceIds, outputField,
                    null, null, collection, texts);
        }
    }
}
