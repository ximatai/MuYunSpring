package net.ximatai.muyun.spring.platform.logging;

import net.ximatai.muyun.spring.ability.logging.BusinessLogEventType;
import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionResult;

import java.util.Objects;

/** Result of applying one persisted event-type policy. */
public record BusinessLogRetentionRunResult(
        BusinessLogEventType eventType,
        int retentionDays,
        BusinessLogRetentionResult result
) {
    public BusinessLogRetentionRunResult {
        eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        result = Objects.requireNonNull(result, "result must not be null");
    }
}
