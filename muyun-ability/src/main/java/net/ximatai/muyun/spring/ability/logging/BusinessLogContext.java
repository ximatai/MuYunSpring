package net.ximatai.muyun.spring.ability.logging;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable correlation and attribution snapshot shared by all business log facts.
 *
 * <p>{@code eventId} is the storage idempotency key. {@code traceId} is only a correlation
 * value; callers must not use it for authorization or identity decisions.</p>
 */
public record BusinessLogContext(
        String eventId,
        Instant occurredAt,
        Instant capturedAt,
        String traceId,
        String tenantId,
        String operatorId,
        String operatorOrganizationId,
        String moduleAlias,
        String actionCode
) {
    private static final Pattern TRACE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]*");

    public BusinessLogContext {
        eventId = required(eventId, "eventId", 128);
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        capturedAt = Objects.requireNonNull(capturedAt, "capturedAt must not be null");
        traceId = optional(traceId, "traceId", 128);
        if (traceId == null) {
            traceId = eventId;
        } else if (!TRACE_ID.matcher(traceId).matches()) {
            throw new IllegalArgumentException("traceId contains unsupported characters");
        }
        tenantId = optional(tenantId, "tenantId", 128);
        operatorId = optional(operatorId, "operatorId", 128);
        operatorOrganizationId = optional(operatorOrganizationId, "operatorOrganizationId", 128);
        moduleAlias = optional(moduleAlias, "moduleAlias", 192);
        actionCode = optional(actionCode, "actionCode", 128);
    }

    /**
     * Source-compatible context constructor for facts captured before organization attribution.
     */
    public BusinessLogContext(String eventId, Instant occurredAt, Instant capturedAt, String traceId,
                              String tenantId, String operatorId, String moduleAlias, String actionCode) {
        this(eventId, occurredAt, capturedAt, traceId, tenantId, operatorId, null, moduleAlias, actionCode);
    }

    public static BusinessLogContext capturedNow(String eventId, Instant occurredAt, String traceId,
                                                 String tenantId, String operatorId,
                                                 String moduleAlias, String actionCode) {
        return new BusinessLogContext(eventId, occurredAt, Instant.now(), traceId, tenantId, operatorId,
                null, moduleAlias, actionCode);
    }

    public static BusinessLogContext capturedNow(String eventId, Instant occurredAt, String traceId,
                                                 String tenantId, String operatorId, String operatorOrganizationId,
                                                 String moduleAlias, String actionCode) {
        return new BusinessLogContext(eventId, occurredAt, Instant.now(), traceId, tenantId, operatorId,
                operatorOrganizationId, moduleAlias, actionCode);
    }

    static String required(String value, String name, int maximumLength) {
        String normalized = optional(value, name, maximumLength);
        if (normalized == null) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    static String optional(String value, String name, int maximumLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must not exceed " + maximumLength + " characters");
        }
        return normalized;
    }
}
