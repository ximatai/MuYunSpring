package net.ximatai.muyun.spring.ability.logging;

/** Typed aggregates for action facts, including explicit counts for unavailable metrics. */
public record ActionLogStatistics(
        long executionCount,
        long successCount,
        long failureCount,
        long rejectedCount,
        long knownDurationCount,
        long totalDurationMillis,
        long knownAffectedRecordCount,
        long totalAffectedRecordCount,
        boolean complete
) { }
