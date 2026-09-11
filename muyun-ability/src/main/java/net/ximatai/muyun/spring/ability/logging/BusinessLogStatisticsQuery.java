package net.ximatai.muyun.spring.ability.logging;

import java.time.Instant;

/** Bounded, service-side statistics request; it deliberately carries no authorization decision. */
public record BusinessLogStatisticsQuery(
        Instant occurredFrom,
        Instant occurredTo,
        String tenantId,
        String moduleAlias,
        String actionCode,
        int maximumEvents
) {
    public BusinessLogStatisticsQuery {
        if (occurredFrom != null && occurredTo != null && occurredFrom.isAfter(occurredTo)) {
            throw new IllegalArgumentException("occurredFrom must not be after occurredTo");
        }
        tenantId = BusinessLogContext.optional(tenantId, "tenantId", 128);
        moduleAlias = BusinessLogContext.optional(moduleAlias, "moduleAlias", 192);
        actionCode = BusinessLogContext.optional(actionCode, "actionCode", 128);
        if (maximumEvents < 1 || maximumEvents > 10_000) {
            throw new IllegalArgumentException("maximumEvents must be between 1 and 10000");
        }
    }

    public static BusinessLogStatisticsQuery recent(int maximumEvents) {
        return new BusinessLogStatisticsQuery(null, null, null, null, null, maximumEvents);
    }
}
