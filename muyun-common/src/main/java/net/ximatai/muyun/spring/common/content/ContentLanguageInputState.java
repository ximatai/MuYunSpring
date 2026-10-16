package net.ximatai.muyun.spring.common.content;

import java.util.Map;

/** Transient save intent, with rollback restoration that never replaces a newer caller decision. */
public final class ContentLanguageInputState {
    private Map<String, ContentWriteContext.SourceInput> inputs = Map.of();
    private long generation;

    public Map<String, ContentWriteContext.SourceInput> inputs() { return inputs; }

    public void inputs(Map<String, ContentWriteContext.SourceInput> value) {
        if (value != null && value.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getValue() == null))
            throw new IllegalArgumentException("Content language entries must contain a field and source input");
        inputs = value == null ? Map.of() : Map.copyOf(value);
        generation++;
    }

    /** Consume once for this transaction; the returned action may restore it on rollback. */
    public Runnable consume() {
        if (inputs.isEmpty()) return () -> {};
        var consumed = inputs;
        long consumedGeneration = generation;
        inputs = Map.of();
        return () -> {
            if (generation == consumedGeneration) inputs(consumed);
        };
    }
}
