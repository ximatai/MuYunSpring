package net.ximatai.muyun.spring.ability.logging;

import java.time.Instant;
import java.util.Set;

/** First-phase read filters, deliberately limited to the common operational conditions. */
public record BusinessLogQuery(
        Instant occurredFrom,
        Instant occurredTo,
        String tenantId,
        Set<BusinessLogEventType> eventTypes,
        String operatorId,
        Set<String> operatorOrganizationIds,
        String moduleAlias,
        String actionCode,
        String errorCode,
        String loginAccount,
        LoginLogDetails.LoginOutcome loginOutcome,
        Integer httpStatus,
        BusinessLogCursor cursor,
        int limit
) {
    public BusinessLogQuery {
        if (occurredFrom != null && occurredTo != null && occurredFrom.isAfter(occurredTo)) {
            throw new IllegalArgumentException("occurredFrom must not be after occurredTo");
        }
        tenantId = BusinessLogContext.optional(tenantId, "tenantId", 128);
        if (eventTypes != null) {
            if (eventTypes.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("eventTypes must not contain null");
            }
            eventTypes = Set.copyOf(eventTypes);
        }
        operatorId = BusinessLogContext.optional(operatorId, "operatorId", 128);
        operatorOrganizationIds = normalizeIds(operatorOrganizationIds, "operatorOrganizationIds");
        moduleAlias = BusinessLogContext.optional(moduleAlias, "moduleAlias", 192);
        actionCode = BusinessLogContext.optional(actionCode, "actionCode", 128);
        errorCode = BusinessLogContext.optional(errorCode, "errorCode", 128);
        loginAccount = BusinessLogContext.optional(loginAccount, "loginAccount", 256);
        if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
            throw new IllegalArgumentException("httpStatus must be between 100 and 599");
        }
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
    }

    /** Source-compatible query constructor without login-outcome or HTTP-status filters. */
    public BusinessLogQuery(Instant occurredFrom, Instant occurredTo, String tenantId,
                            Set<BusinessLogEventType> eventTypes, String operatorId,
                            Set<String> operatorOrganizationIds, String moduleAlias, String actionCode,
                            String errorCode, BusinessLogCursor cursor, int limit) {
        this(occurredFrom, occurredTo, tenantId, eventTypes, operatorId, operatorOrganizationIds,
                moduleAlias, actionCode, errorCode, null, null, null, cursor, limit);
    }

    /** Source-compatible query constructor without a login-account filter. */
    public BusinessLogQuery(Instant occurredFrom, Instant occurredTo, String tenantId,
                            Set<BusinessLogEventType> eventTypes, String operatorId,
                            Set<String> operatorOrganizationIds, String moduleAlias, String actionCode,
                            String errorCode, LoginLogDetails.LoginOutcome loginOutcome, Integer httpStatus,
                            BusinessLogCursor cursor, int limit) {
        this(occurredFrom, occurredTo, tenantId, eventTypes, operatorId, operatorOrganizationIds,
                moduleAlias, actionCode, errorCode, null, loginOutcome, httpStatus, cursor, limit);
    }

    /** Source-compatible query constructor without event, operator or organization filters. */
    public BusinessLogQuery(Instant occurredFrom, Instant occurredTo, String tenantId, String moduleAlias,
                            String actionCode, String errorCode, BusinessLogCursor cursor, int limit) {
        this(occurredFrom, occurredTo, tenantId, null, null, null, moduleAlias, actionCode, errorCode,
                null, null, null, cursor, limit);
    }

    public static BusinessLogQuery newest(int limit) {
        return new BusinessLogQuery(null, null, null, null, null, null, null, null, null,
                null, null, null, null, limit);
    }

    static Set<String> normalizeIds(Set<String> values, String name) {
        if (values == null) {
            return null;
        }
        return values.stream()
                .map(value -> BusinessLogContext.optional(value, name, 128))
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
