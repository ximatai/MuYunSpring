package net.ximatai.muyun.spring.ability.logging;

import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;

/** Typed details for one module action execution. */
public record ActionLogDetails(
        ActionOutcome outcome,
        String executorType,
        Long durationMillis,
        Long affectedRecordCount,
        String failureStage,
        LogText message,
        String entityAlias,
        String recordId,
        RuntimeMutationSource mutationSource
) implements BusinessLogDetails {
    /** Retains source compatibility for callers that do have concrete execution metrics. */
    public ActionLogDetails(ActionOutcome outcome,
                            String executorType,
                            long durationMillis,
                            long affectedRecordCount,
                            String failureStage,
                            LogText message) {
        this(outcome, executorType, Long.valueOf(durationMillis), Long.valueOf(affectedRecordCount), failureStage,
                message, null, null, null);
    }

    /** Source-compatible constructor for action facts captured before business-object attribution. */
    public ActionLogDetails(ActionOutcome outcome,
                            String executorType,
                            Long durationMillis,
                            Long affectedRecordCount,
                            String failureStage,
                            LogText message) {
        this(outcome, executorType, durationMillis, affectedRecordCount, failureStage, message,
                null, null, null);
    }

    public ActionLogDetails {
        outcome = java.util.Objects.requireNonNull(outcome, "outcome must not be null");
        executorType = BusinessLogContext.optional(executorType, "executorType", 64);
        if ((durationMillis != null && durationMillis < 0)
                || (affectedRecordCount != null && affectedRecordCount < 0)) {
            throw new IllegalArgumentException("durationMillis and affectedRecordCount must not be negative");
        }
        failureStage = BusinessLogContext.optional(failureStage, "failureStage", 128);
        entityAlias = BusinessLogContext.optional(entityAlias, "entityAlias", 192);
        recordId = BusinessLogContext.optional(recordId, "recordId", 128);
    }

    public enum ActionOutcome { SUCCESS, FAILURE, REJECTED }
}
