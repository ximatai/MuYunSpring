package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.logging.ActionLogDetails;
import net.ximatai.muyun.spring.ability.logging.ActionLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogEventType;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorIdentity;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorIdentityKey;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorIdentityLookup;
import net.ximatai.muyun.spring.ability.logging.LoginLogDetails;
import net.ximatai.muyun.spring.ability.logging.LoginLogEvent;
import net.ximatai.muyun.spring.ability.logging.PageAccessLogDetails;
import net.ximatai.muyun.spring.ability.logging.PageAccessLogEvent;
import net.ximatai.muyun.spring.ability.logging.RequestErrorLogDetails;
import net.ximatai.muyun.spring.ability.logging.RequestErrorLogEvent;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Safe HTTP projection of a business-log fact. Internal exception diagnostics are never included. */
public record BusinessLogEventResponse(
        String eventId,
        BusinessLogEventType eventType,
        Instant occurredAt,
        Instant capturedAt,
        String traceId,
        String tenantId,
        String operatorId,
        String operatorOrganizationId,
        String moduleAlias,
        String actionCode,
        BusinessLogOperatorIdentity operatorIdentity,
        String outcome,
        Object details
) {
    public static BusinessLogEventResponse from(BusinessLogEvent event) {
        return from(event, Map.of());
    }

    public static BusinessLogEventResponse from(BusinessLogEvent event,
                                                BusinessLogOperatorIdentityLookup identityLookup) {
        return from(event, identityLookup == null ? Map.of() : identityLookup.resolve(identityKeys(event)));
    }

    static BusinessLogEventResponse from(BusinessLogEvent event,
                                         Map<BusinessLogOperatorIdentityKey, BusinessLogOperatorIdentity> identities) {
        Objects.requireNonNull(event, "event must not be null");
        var context = event.context();
        BusinessLogOperatorIdentity identity = identityKey(event)
                .map(key -> identities == null ? null : identities.get(key))
                .orElse(null);
        return new BusinessLogEventResponse(context.eventId(), event.eventType(), context.occurredAt(),
                context.capturedAt(), context.traceId(), context.tenantId(), context.operatorId(),
                context.operatorOrganizationId(), context.moduleAlias(), context.actionCode(), identity,
                outcome(event), safeDetails(event));
    }

    static Optional<BusinessLogOperatorIdentityKey> identityKey(BusinessLogEvent event) {
        if (event == null) {
            return Optional.empty();
        }
        var context = event.context();
        if (context.operatorId() == null) {
            return Optional.empty();
        }
        return Optional.of(new BusinessLogOperatorIdentityKey(context.tenantId(), context.operatorId(),
                context.operatorOrganizationId()));
    }

    private static java.util.Collection<BusinessLogOperatorIdentityKey> identityKeys(BusinessLogEvent event) {
        return identityKey(event).<java.util.Collection<BusinessLogOperatorIdentityKey>>map(java.util.List::of)
                .orElseGet(java.util.List::of);
    }

    private static Object safeDetails(BusinessLogEvent event) {
        return switch (event) {
            case ActionLogEvent action -> action.details();
            case PageAccessLogEvent pageAccess -> pageAccess.details();
            case LoginLogEvent login -> login.details();
            case RequestErrorLogEvent requestError -> safeRequestErrorDetails(requestError.details());
        };
    }

    private static String outcome(BusinessLogEvent event) {
        return switch (event) {
            case ActionLogEvent action -> action.details().outcome().name();
            case LoginLogEvent login -> login.details().outcome().name();
            case PageAccessLogEvent ignored -> null;
            case RequestErrorLogEvent ignored -> "FAILURE";
        };
    }

    private static RequestErrorLogSafeDetails safeRequestErrorDetails(RequestErrorLogDetails details) {
        return new RequestErrorLogSafeDetails(details.method(), details.path(), details.endpointId(),
                details.durationMillis(), details.httpStatus(), details.errorCode(), details.responseSummary(),
                details.failureStage(), details.responseCompleted());
    }

    /** Request details visible to every administrator with access to the error event. */
    public record RequestErrorLogSafeDetails(
            String method,
            String path,
            String endpointId,
            long durationMillis,
            int httpStatus,
            String errorCode,
            net.ximatai.muyun.spring.ability.logging.LogText responseSummary,
            String failureStage,
            boolean responseCompleted
    ) { }
}
