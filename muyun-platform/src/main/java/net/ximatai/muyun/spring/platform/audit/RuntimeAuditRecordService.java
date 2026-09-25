package net.ximatai.muyun.spring.platform.audit;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.event.ActionEventPayload;
import net.ximatai.muyun.spring.ability.event.RuntimeEvent;
import net.ximatai.muyun.spring.ability.event.RuntimeEventAuditContext;
import net.ximatai.muyun.spring.ability.event.RuntimeEventType;
import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.ActingContext;
import net.ximatai.muyun.spring.common.identity.ActingContextHolder;
import net.ximatai.muyun.spring.common.identity.BusinessPrincipal;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class RuntimeAuditRecordService extends AbstractAbilityService<RuntimeAuditRecord> {
    public static final String MODULE_ALIAS = "platform.runtime_audit_record";
    private final RuntimeAuditPayloadSanitizer payloadSanitizer;

    @Autowired
    public RuntimeAuditRecordService(BaseDao<RuntimeAuditRecord, String> auditRecordDao) {
        this(auditRecordDao, new RuntimeAuditPayloadSanitizer());
    }

    public RuntimeAuditRecordService(BaseDao<RuntimeAuditRecord, String> auditRecordDao,
                                     RuntimeAuditPayloadSanitizer payloadSanitizer) {
        super(MODULE_ALIAS, RuntimeAuditRecord.class, auditRecordDao);
        this.payloadSanitizer = payloadSanitizer == null ? new RuntimeAuditPayloadSanitizer() : payloadSanitizer;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String record(RuntimeEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        RuntimeAuditRecord record = new RuntimeAuditRecord();
        record.setEventId(event.eventId());
        record.setTenantId(event.tenantId());
        record.setTraceId(event.traceId());
        record.setEventType(event.eventType());
        record.setModuleAlias(event.moduleAlias());
        record.setEntityAlias(event.entityAlias());
        record.setRecordId(event.recordId());
        record.setActionCode(event.actionCode());
        fillActionPayload(record, event);
        record.setSystemContext(event.systemContext());
        record.setSystemReason(event.systemReason());
        record.setOperatorId(event.operatorId());
        record.setOperatorType(event.operatorType());
        record.setAuthorizationDecision(event.authorizationDecision());
        record.setAuthorizationPermissionCode(event.authorizationPermissionCode());
        record.setAuthorizationPermissionActionCode(event.authorizationPermissionActionCode());
        fillActingContext(record, event);
        record.setMutationSource(event.mutationSource());
        Map<String, Object> sanitizedPayload = payloadSanitizer.sanitize(event.payload());
        record.setPayloadText(payloadText(sanitizedPayload));
        record.setOccurredAt(event.occurredAt());
        if (event.tenantId() == null) {
            try (TenantContext.Scope ignored = TenantContext.system("runtime audit persist")) {
                return insert(record);
            }
        }
        return insert(record);
    }

    private void fillActingContext(RuntimeAuditRecord record, RuntimeEvent event) {
        if (fillActingContext(record, event.auditContext())) {
            return;
        }
        ActingContext actingContext = ActingContextHolder.current()
                .filter(acting -> acting.matches(event.moduleAlias(), auditActionCode(event)))
                .orElse(null);
        if (actingContext == null) {
            return;
        }
        BusinessPrincipal principal = actingContext.principal();
        record.setActingDelegationId(actingContext.delegationId());
        record.setActingPrincipalUserId(principal.userId());
        record.setActingPrincipalEmployeeId(principal.employeeId());
        record.setActingPrincipalOrganizationId(principal.organizationId());
        record.setActingPrincipalDepartmentId(principal.departmentId());
        record.setActingPrincipalEmployeePositionId(principal.employeePositionId());
    }

    private boolean fillActingContext(RuntimeAuditRecord record, Map<String, Object> auditContext) {
        String delegationId = RuntimeEventAuditContext.text(auditContext, RuntimeEventAuditContext.ACTING_DELEGATION_ID);
        if (delegationId == null) {
            return false;
        }
        record.setActingDelegationId(delegationId);
        record.setActingPrincipalUserId(RuntimeEventAuditContext.text(
                auditContext, RuntimeEventAuditContext.ACTING_PRINCIPAL_USER_ID));
        record.setActingPrincipalEmployeeId(RuntimeEventAuditContext.text(
                auditContext, RuntimeEventAuditContext.ACTING_PRINCIPAL_EMPLOYEE_ID));
        record.setActingPrincipalOrganizationId(RuntimeEventAuditContext.text(
                auditContext, RuntimeEventAuditContext.ACTING_PRINCIPAL_ORGANIZATION_ID));
        record.setActingPrincipalDepartmentId(RuntimeEventAuditContext.text(
                auditContext, RuntimeEventAuditContext.ACTING_PRINCIPAL_DEPARTMENT_ID));
        record.setActingPrincipalEmployeePositionId(RuntimeEventAuditContext.text(
                auditContext, RuntimeEventAuditContext.ACTING_PRINCIPAL_EMPLOYEE_POSITION_ID));
        return true;
    }

    private String auditActionCode(RuntimeEvent event) {
        if (event.actionCode() != null && !event.actionCode().isBlank()) {
            return event.actionCode();
        }
        return ActionExecutionContextHolder.current()
                .filter(context -> context.moduleAlias().equals(event.moduleAlias()))
                .map(ActionExecutionContext::actionCode)
                .orElse(null);
    }

    private void fillActionPayload(RuntimeAuditRecord record, RuntimeEvent event) {
        if (event.eventType() == RuntimeEventType.ACTION_EXECUTED) {
            record.setExecutorType(ActionEventPayload.text(event.payload(), ActionEventPayload.EXECUTOR_TYPE));
            record.setActionLevel(ActionEventPayload.text(event.payload(), ActionEventPayload.ACTION_LEVEL));
            record.setResultType(ActionEventPayload.text(event.payload(), ActionEventPayload.RESULT_TYPE));
            record.setResultMessage(ActionEventPayload.text(event.payload(), ActionEventPayload.MESSAGE));
            record.setRefreshRequested(ActionEventPayload.bool(event.payload(), ActionEventPayload.REFRESH));
            record.setRedirectTo(ActionEventPayload.text(event.payload(), ActionEventPayload.REDIRECT_TO));
            record.setResultText(payloadSanitizer.sanitizeText(ActionEventPayload.RESULT,
                    ActionEventPayload.text(event.payload(), ActionEventPayload.RESULT)));
            return;
        }
        if (event.eventType() == RuntimeEventType.ACTION_FAILED) {
            record.setExecutorType(ActionEventPayload.text(event.payload(), ActionEventPayload.EXECUTOR_TYPE));
            record.setActionLevel(ActionEventPayload.text(event.payload(), ActionEventPayload.ACTION_LEVEL));
            record.setFailureStage(ActionEventPayload.text(event.payload(), ActionEventPayload.FAILURE_STAGE));
            record.setErrorMessage(ActionEventPayload.text(event.payload(), ActionEventPayload.ERROR_MESSAGE));
            record.setErrorType(ActionEventPayload.text(event.payload(), ActionEventPayload.ERROR_TYPE));
        }
    }

    public Criteria traceCriteria(String traceId) {
        return Criteria.of().eq("traceId", requireText(traceId, "traceId"));
    }

    public Criteria actionCriteria(String moduleAlias, String actionCode) {
        return Criteria.of()
                .eq("moduleAlias", requireText(moduleAlias, "moduleAlias"))
                .eq("actionCode", requireText(actionCode, "actionCode"));
    }

    public Criteria eventTypeCriteria(RuntimeEventType eventType) {
        if (eventType == null) {
            throw new PlatformException("Runtime audit eventType must not be null");
        }
        return Criteria.of().eq("eventType", eventType);
    }

    public Criteria recordCriteria(String moduleAlias, String entityAlias, String recordId) {
        return Criteria.of()
                .eq("moduleAlias", requireText(moduleAlias, "moduleAlias"))
                .eq("entityAlias", requireText(entityAlias, "entityAlias"))
                .eq("recordId", requireText(recordId, "recordId"));
    }

    public Criteria failedActionCriteria(String moduleAlias) {
        return Criteria.of()
                .eq("moduleAlias", requireText(moduleAlias, "moduleAlias"))
                .eq("eventType", RuntimeEventType.ACTION_FAILED);
    }

    public Criteria resultTypeCriteria(String resultType) {
        return Criteria.of()
                .eq("eventType", RuntimeEventType.ACTION_EXECUTED)
                .eq("resultType", requireText(resultType, "resultType"));
    }

    public List<RuntimeAuditRecord> traceEvents(String traceId, PageRequest pageRequest) {
        return list(traceCriteria(traceId), pageRequest, Sort.asc("occurredAt"));
    }

    public List<RuntimeAuditRecord> recordTimeline(String moduleAlias,
                                                   String entityAlias,
                                                   String recordId,
                                                   PageRequest pageRequest) {
        return list(recordCriteria(moduleAlias, entityAlias, recordId), pageRequest, Sort.asc("occurredAt"));
    }

    public List<RuntimeAuditRecord> actionEvents(String moduleAlias, String actionCode, PageRequest pageRequest) {
        return list(actionCriteria(moduleAlias, actionCode), pageRequest, Sort.desc("occurredAt"));
    }

    public List<RuntimeAuditRecord> failedActions(String moduleAlias, PageRequest pageRequest) {
        return list(failedActionCriteria(moduleAlias), pageRequest, Sort.desc("occurredAt"));
    }

    public List<RuntimeAuditRecord> actionResults(String resultType, PageRequest pageRequest) {
        return list(resultTypeCriteria(resultType), pageRequest, Sort.desc("occurredAt"));
    }

    @Override
    public void beforeInsert(RuntimeAuditRecord record) {
        if (getDao().count(Criteria.of().eq("eventId", record.getEventId())) > 0) {
            throw new PlatformException("Runtime audit eventId must be unique: " + record.getEventId());
        }
        if (record.getSystemContext() == null) {
            record.setSystemContext(Boolean.FALSE);
        }
        if (record.getMutationSource() == RuntimeMutationSource.SYSTEM) {
            record.setSystemContext(Boolean.TRUE);
        }
        if (record.getSystemReason() != null && record.getSystemReason().isBlank()) {
            record.setSystemReason(null);
        }
        if (Boolean.TRUE.equals(record.getSystemContext()) && record.getSystemReason() == null) {
            throw new PlatformException("Runtime audit systemReason must not be blank for system context");
        }
    }

    private String payloadText(Map<String, Object> payload) {
        return payload == null || payload.isEmpty() ? null : payload.toString();
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new PlatformException("Runtime audit " + fieldName + " must not be blank");
        }
        return value;
    }
}
