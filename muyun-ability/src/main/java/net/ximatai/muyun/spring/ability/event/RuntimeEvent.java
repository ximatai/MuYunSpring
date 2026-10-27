package net.ximatai.muyun.spring.ability.event;

import net.ximatai.muyun.spring.common.platform.ActionAuthorizationResult;
import net.ximatai.muyun.spring.common.identity.JobExecutionContext;
import net.ximatai.muyun.spring.common.web.RequestTraceContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record RuntimeEvent(
        String eventId,
        String traceId,
        RuntimeEventType eventType,
        String moduleAlias,
        String entityAlias,
        String recordId,
        String actionCode,
        String tenantId,
        boolean systemContext,
        String systemReason,
        String operatorId,
        String operatorType,
        String authorizationDecision,
        String authorizationPermissionCode,
        String authorizationPermissionActionCode,
        RuntimeMutationSource mutationSource,
        Map<String, Object> payload,
        Map<String, Object> auditContext,
        Instant occurredAt
) {
    public RuntimeEvent(String eventId,
                        String traceId,
                        RuntimeEventType eventType,
                        String moduleAlias,
                        String entityAlias,
                        String recordId,
                        String actionCode,
                        String tenantId,
                        boolean systemContext,
                        String systemReason,
                        RuntimeMutationSource mutationSource,
                        Map<String, Object> payload,
                        Instant occurredAt) {
        this(eventId, traceId, eventType, moduleAlias, entityAlias, recordId, actionCode, tenantId, systemContext,
                systemReason, null, null, null, null, null, mutationSource, payload, null, occurredAt);
    }

    public RuntimeEvent(String eventId,
                        String traceId,
                        RuntimeEventType eventType,
                        String moduleAlias,
                        String entityAlias,
                        String recordId,
                        String actionCode,
                        String tenantId,
                        boolean systemContext,
                        String operatorId,
                        String operatorType,
                        String authorizationDecision,
                        RuntimeMutationSource mutationSource,
                        Map<String, Object> payload,
                        Instant occurredAt) {
        this(eventId, traceId, eventType, moduleAlias, entityAlias, recordId, actionCode, tenantId, systemContext,
                null, operatorId, operatorType, authorizationDecision, null, null, mutationSource, payload, null, occurredAt);
    }

    public RuntimeEvent(String eventId,
                        String traceId,
                        RuntimeEventType eventType,
                        String moduleAlias,
                        String entityAlias,
                        String recordId,
                        String actionCode,
                        String tenantId,
                        boolean systemContext,
                        String operatorId,
                        String operatorType,
                        String authorizationDecision,
                        String authorizationPermissionCode,
                        String authorizationPermissionActionCode,
                        RuntimeMutationSource mutationSource,
                        Map<String, Object> payload,
                        Instant occurredAt) {
        this(eventId, traceId, eventType, moduleAlias, entityAlias, recordId, actionCode, tenantId, systemContext,
                null, operatorId, operatorType, authorizationDecision, authorizationPermissionCode,
                authorizationPermissionActionCode, mutationSource, payload, null, occurredAt);
    }

    public RuntimeEvent(String eventId,
                        String traceId,
                        RuntimeEventType eventType,
                        String moduleAlias,
                        String entityAlias,
                        String recordId,
                        String actionCode,
                        String tenantId,
                        boolean systemContext,
                        String systemReason,
                        String operatorId,
                        String operatorType,
                        String authorizationDecision,
                        RuntimeMutationSource mutationSource,
                        Map<String, Object> payload,
                        Instant occurredAt) {
        this(eventId, traceId, eventType, moduleAlias, entityAlias, recordId, actionCode, tenantId, systemContext,
                systemReason, operatorId, operatorType, authorizationDecision, null, null, mutationSource, payload, null, occurredAt);
    }

    public RuntimeEvent(String eventId,
                        String traceId,
                        RuntimeEventType eventType,
                        String moduleAlias,
                        String entityAlias,
                        String recordId,
                        String actionCode,
                        String tenantId,
                        boolean systemContext,
                        RuntimeMutationSource mutationSource,
                        Map<String, Object> payload,
                        Instant occurredAt) {
        this(eventId, traceId, eventType, moduleAlias, entityAlias, recordId, actionCode, tenantId, systemContext,
                null, null, null, null, null, null, mutationSource, payload, null, occurredAt);
    }

    public RuntimeEvent {
        Objects.requireNonNull(eventType, "eventType must not be null");
        requireText(moduleAlias, "moduleAlias");
        if (eventType.requiresEntityAlias()) {
            requireText(entityAlias, "entityAlias");
        }
        Objects.requireNonNull(mutationSource, "mutationSource must not be null");
        if (eventType == RuntimeEventType.ACTION_EXECUTED || eventType == RuntimeEventType.ACTION_FAILED) {
            requireText(actionCode, "actionCode");
        }
        eventId = eventId == null || eventId.isBlank() ? UUID.randomUUID().toString() : eventId;
        if (JobExecutionContext.current().isPresent()) {
            traceId = RequestTraceContext.currentTraceId().orElse(traceId);
        }
        traceId = traceId == null || traceId.isBlank() ? eventId : traceId;
        if (TenantContext.backgroundJobType().isPresent()) {
            if (TenantContext.isTenantSystem()) tenantId = TenantContext.currentTenantId().orElseThrow();
            systemContext = true;
            systemReason = TenantContext.systemReason().orElseThrow();
            operatorId = TenantContext.systemOperatorId().orElseThrow();
            operatorType = ActionAuthorizationResult.OPERATOR_BACKGROUND_JOB;
        }
        systemReason = systemReason == null || systemReason.isBlank() ? null : systemReason.trim();
        if (mutationSource == RuntimeMutationSource.SYSTEM) {
            systemContext = true;
        }
        if (systemContext && systemReason == null) {
            throw new IllegalArgumentException("system runtime event requires systemReason");
        }
        payload = payload == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(payload));
        auditContext = auditContext == null
                ? RuntimeEventAuditContext.capture(moduleAlias, actionCode)
                : Map.copyOf(new LinkedHashMap<>(auditContext));
        if (JobExecutionContext.current().isPresent()) {
            Map<String, Object> merged = new LinkedHashMap<>(auditContext);
            RuntimeEventAuditContext.capture(moduleAlias, actionCode).forEach((key, value) -> {
                if (key.equals(RuntimeEventAuditContext.JOB_TYPE) || key.equals(RuntimeEventAuditContext.JOB_PLAN_ID)
                        || key.equals(RuntimeEventAuditContext.JOB_RUN_ID) || key.equals(RuntimeEventAuditContext.JOB_IDENTITY_MODE)) {
                    merged.put(key, value);
                }
            });
            auditContext = Map.copyOf(merged);
        }
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }

    public static RuntimeEvent of(RuntimeEventType eventType,
                                  String moduleAlias,
                                  String entityAlias,
                                  String recordId,
                                  String actionCode,
                                  String tenantId,
                                  boolean systemContext,
                                  String systemReason,
                                  RuntimeMutationSource mutationSource,
                                  Map<String, Object> payload) {
        return of(null, eventType, moduleAlias, entityAlias, recordId, actionCode,
                tenantId, systemContext, systemReason, mutationSource, payload);
    }

    public static RuntimeEvent of(String traceId,
                                  RuntimeEventType eventType,
                                  String moduleAlias,
                                  String entityAlias,
                                  String recordId,
                                  String actionCode,
                                  String tenantId,
                                  boolean systemContext,
                                  String systemReason,
                                  RuntimeMutationSource mutationSource,
                                  Map<String, Object> payload) {
        // Snapshot attribution when the fact is produced, never when an asynchronous listener consumes it.
        var actor = net.ximatai.muyun.spring.common.identity.CurrentUserContext.currentUser().orElse(null);
        String actorId = actor == null ? null : actor.userId();
        var action = net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder.current()
                .filter(context -> context.moduleAlias().equals(moduleAlias))
                .filter(context -> actionCode == null || actionCode.equals(context.actionCode()))
                .filter(context -> Objects.equals(context.currentUser().map(user -> user.userId()).orElse(null), actorId))
                .orElse(null);
        var authorization = action == null ? null : action.authorizationResult();
        String effectiveOperatorId = TenantContext.systemOperatorId().orElse(actorId);
        if (authorization != null && !Objects.equals(authorization.operatorId(), effectiveOperatorId)) authorization = null;
        String operatorId = authorization != null ? authorization.operatorId() : actor == null ? null : actor.userId();
        String operatorType = authorization != null ? authorization.operatorType() : actor == null ? null
                : actor.system() ? ActionAuthorizationResult.OPERATOR_SYSTEM : ActionAuthorizationResult.OPERATOR_USER;
        return new RuntimeEvent(null, traceId, eventType, moduleAlias, entityAlias, recordId, actionCode,
                tenantId, systemContext, systemReason, operatorId, operatorType,
                authorization == null ? null : authorization.decision(),
                authorization == null ? null : authorization.permissionCode(),
                authorization == null ? null : authorization.permissionActionCode(),
                mutationSource, payload, null, null);
    }

    public static RuntimeEvent of(RuntimeEventType eventType,
                                  String moduleAlias,
                                  String entityAlias,
                                  String recordId,
                                  String actionCode,
                                  String tenantId,
                                  boolean systemContext,
                                  RuntimeMutationSource mutationSource,
                                  Map<String, Object> payload) {
        return of(eventType, moduleAlias, entityAlias, recordId, actionCode, tenantId, systemContext,
                null, mutationSource, payload);
    }

    public static RuntimeEvent of(String traceId,
                                  RuntimeEventType eventType,
                                  String moduleAlias,
                                  String entityAlias,
                                  String recordId,
                                  String actionCode,
                                  String tenantId,
                                  boolean systemContext,
                                  RuntimeMutationSource mutationSource,
                                  Map<String, Object> payload) {
        return of(traceId, eventType, moduleAlias, entityAlias, recordId, actionCode, tenantId, systemContext,
                null, mutationSource, payload);
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
