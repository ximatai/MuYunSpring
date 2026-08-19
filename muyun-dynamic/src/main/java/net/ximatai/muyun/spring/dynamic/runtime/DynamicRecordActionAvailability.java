package net.ximatai.muyun.spring.dynamic.runtime;

import java.util.Map;

/**
 * Source-neutral, bounded record-action projection used by explorer surfaces.
 * It carries only already-authorized record decisions, never executable action state.
 */
public record DynamicRecordActionAvailability(
        String recordId,
        Map<String, DynamicActionAvailability> actions
) {
    public DynamicRecordActionAvailability {
        actions = actions == null ? Map.of() : Map.copyOf(actions);
    }
}
