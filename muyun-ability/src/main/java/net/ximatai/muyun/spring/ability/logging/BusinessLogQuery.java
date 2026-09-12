package net.ximatai.muyun.spring.ability.logging;

import java.time.Instant;

/** First-phase read filters, deliberately limited to the common operational conditions. */
public record BusinessLogQuery(
        Instant occurredFrom,
        Instant occurredTo,
        String tenantId,
        String moduleAlias,
        String actionCode,
        String errorCode,
        BusinessLogCursor cursor,
        int limit
) {
    public BusinessLogQuery {
        if (occurredFrom != null && occurredTo != null && occurredFrom.isAfter(occurredTo)) {
            throw new IllegalArgumentException("occurredFrom must not be after occurredTo");
        }
        tenantId = BusinessLogContext.optional(tenantId, "tenantId", 128);
        moduleAlias = BusinessLogContext.optional(moduleAlias, "moduleAlias", 192);
        actionCode = BusinessLogContext.optional(actionCode, "actionCode", 128);
        errorCode = BusinessLogContext.optional(errorCode, "errorCode", 128);
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
    }

    public static BusinessLogQuery newest(int limit) {
        return new BusinessLogQuery(null, null, null, null, null, null, null, limit);
    }
}
