package net.ximatai.muyun.spring.platform.audit;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.event.ActionEventPayload;
import net.ximatai.muyun.spring.ability.event.RuntimeEvent;
import net.ximatai.muyun.spring.ability.event.RuntimeEventType;
import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.ActingContext;
import net.ximatai.muyun.spring.common.identity.ActingContextHolder;
import net.ximatai.muyun.spring.common.identity.BusinessPrincipal;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeAuditRecordServiceContractTest {
    private final TestMemoryDao<RuntimeAuditRecord> dao = new TestMemoryDao<>();
    private final RuntimeAuditRecordService service = new RuntimeAuditRecordService(dao);

    @AfterEach
    void tearDown() {
        ActingContextHolder.clear();
        ActionExecutionContextHolder.clear();
    }

    @Test
    void standardInsertRequiresEventIdentityAndTimestamp() {
        RuntimeAuditRecord record = new RuntimeAuditRecord();
        record.setTraceId("trace-required");
        record.setEventType(RuntimeEventType.AFTER_UPDATE);
        record.setModuleAlias("sales.contract");
        record.setMutationSource(RuntimeMutationSource.BUSINESS);
        record.setOccurredAt(Instant.now());
        assertThatThrownBy(() -> service.insert(record))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("eventId");
        record.setEventId("event-required");
        record.setOccurredAt(null);
        assertThatThrownBy(() -> service.insert(record))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("occurredAt");
        assertThat(dao.count(Criteria.of())).isZero();
    }

    @Test
    void shouldPersistRuntimeEventAsAuditRecord() {
        RuntimeEvent event = event();

        String id = service.record(event);

        RuntimeAuditRecord record = service.select(id);
        assertThat(record.getId()).isNotBlank();
        assertThat(record.getEventId()).isEqualTo("event-1");
        assertThat(record.getTenantId()).isEqualTo("tenant-1");
        assertThat(record.getTraceId()).isEqualTo("trace-1");
        assertThat(record.getEventType()).isEqualTo(RuntimeEventType.ACTION_EXECUTED);
        assertThat(record.getModuleAlias()).isEqualTo("sales.contract");
        assertThat(record.getEntityAlias()).isEqualTo("contract");
        assertThat(record.getRecordId()).isEqualTo("contract-1");
        assertThat(record.getActionCode()).isEqualTo("approve");
        assertThat(record.getOperatorId()).isEqualTo("user-1");
        assertThat(record.getOperatorType()).isEqualTo("USER");
        assertThat(record.getAuthorizationDecision()).isEqualTo("ROLE_GRANTED");
        assertThat(record.getAuthorizationPermissionCode()).isEqualTo("sales.contract:view");
        assertThat(record.getAuthorizationPermissionActionCode()).isEqualTo("view");
        assertThat(record.getActingDelegationId()).isNull();
        assertThat(record.getExecutorType()).isEqualTo("SERVICE");
        assertThat(record.getActionLevel()).isEqualTo("RECORD");
        assertThat(record.getResultType()).isEqualTo("VALUE");
        assertThat(record.getResultMessage()).isEqualTo("审批通过");
        assertThat(record.getRefreshRequested()).isTrue();
        assertThat(record.getRedirectTo()).isEqualTo("/contracts/contract-1");
        assertThat(record.getResultText()).isEqualTo("approved");
        assertThat(record.getSystemContext()).isFalse();
        assertThat(record.getSystemReason()).isNull();
        assertThat(record.getMutationSource()).isEqualTo(RuntimeMutationSource.ACTION);
        assertThat(record.getPayloadText()).contains("resultType=VALUE");
        assertThat(record.getOccurredAt()).isEqualTo(Instant.parse("2026-06-02T04:00:00Z"));
    }

    @Test
    void shouldPersistActingContextAsLightweightAuditColumns() {
        CurrentUser operator = CurrentUser.tenantUser("assistant-user", "Assistant", "tenant-1");
        BusinessPrincipal principal = BusinessPrincipal.employeePosition(
                "employee-principal", "org-principal", "dept-principal", "position-principal");

        String id;
        try (ActingContextHolder.Scope acting = ActingContextHolder.use(new ActingContext(
                "delegation-1", operator, principal, "sales.contract", "approve"))) {
            id = service.record(event());
        }

        RuntimeAuditRecord record = service.select(id);
        assertThat(record.getOperatorId()).isEqualTo("user-1");
        assertThat(record.getActingDelegationId()).isEqualTo("delegation-1");
        assertThat(record.getActingPrincipalUserId()).isNull();
        assertThat(record.getActingPrincipalEmployeeId()).isEqualTo("employee-principal");
        assertThat(record.getActingPrincipalOrganizationId()).isEqualTo("org-principal");
        assertThat(record.getActingPrincipalDepartmentId()).isEqualTo("dept-principal");
        assertThat(record.getActingPrincipalEmployeePositionId()).isEqualTo("position-principal");
        assertThat(record.getPayloadText()).doesNotContain("employee-principal", "dept-principal");
    }

    @Test
    void shouldMatchActingContextForRecordEventsThroughCurrentActionContext() {
        CurrentUser operator = CurrentUser.tenantUser("assistant-user", "Assistant", "tenant-1");
        BusinessPrincipal principal = BusinessPrincipal.employee(
                "employee-principal", "org-principal", "dept-principal");
        RuntimeEvent event = new RuntimeEvent(
                "record-create-event",
                "record-create-trace",
                RuntimeEventType.AFTER_CREATE,
                "sales.contract",
                "contract",
                "contract-1",
                null,
                "tenant-1",
                false,
                RuntimeMutationSource.BUSINESS,
                Map.of(),
                Instant.parse("2026-06-02T04:25:00Z")
        );

        String id;
        try (ActingContextHolder.Scope acting = ActingContextHolder.use(new ActingContext(
                     "delegation-1", operator, principal, "sales.contract", "create"));
             ActionExecutionContextHolder.Scope action = ActionExecutionContextHolder.use(
                     ActionExecutionContext.ofPlatformAction("sales.contract", PlatformAction.CREATE,
                             java.util.Set.of(), java.util.Optional.of(operator)))) {
            id = service.record(event);
        }

        RuntimeAuditRecord record = service.select(id);
        assertThat(record.getActionCode()).isNull();
        assertThat(record.getActingDelegationId()).isEqualTo("delegation-1");
        assertThat(record.getActingPrincipalEmployeeId()).isEqualTo("employee-principal");
        assertThat(record.getActingPrincipalOrganizationId()).isEqualTo("org-principal");
        assertThat(record.getActingPrincipalDepartmentId()).isEqualTo("dept-principal");
    }

    @Test
    void shouldPersistCapturedActingContextAfterThreadLocalCleared() {
        CurrentUser operator = CurrentUser.tenantUser("assistant-user", "Assistant", "tenant-1");
        BusinessPrincipal principal = BusinessPrincipal.employee(
                "employee-principal", "org-principal", "dept-principal");
        RuntimeEvent event;

        try (ActingContextHolder.Scope acting = ActingContextHolder.use(new ActingContext(
                     "delegation-1", operator, principal, "sales.contract", "create"));
             ActionExecutionContextHolder.Scope action = ActionExecutionContextHolder.use(
                     ActionExecutionContext.ofPlatformAction("sales.contract", PlatformAction.CREATE,
                             java.util.Set.of(), java.util.Optional.of(operator)))) {
            event = new RuntimeEvent(
                    "after-commit-event",
                    "after-commit-trace",
                    RuntimeEventType.AFTER_CREATE,
                    "sales.contract",
                    "contract",
                    "contract-1",
                    null,
                    "tenant-1",
                    false,
                    RuntimeMutationSource.BUSINESS,
                    Map.of(),
                    Instant.parse("2026-06-02T04:25:00Z")
            );
        }

        assertThat(ActingContextHolder.current()).isEmpty();
        assertThat(ActionExecutionContextHolder.current()).isEmpty();

        String id = service.record(event);

        RuntimeAuditRecord record = service.select(id);
        assertThat(record.getActingDelegationId()).isEqualTo("delegation-1");
        assertThat(record.getActingPrincipalEmployeeId()).isEqualTo("employee-principal");
        assertThat(record.getActingPrincipalOrganizationId()).isEqualTo("org-principal");
        assertThat(record.getActingPrincipalDepartmentId()).isEqualTo("dept-principal");
    }

    @Test
    void shouldPersistThroughRuntimeAuditEventListener() {
        RuntimeAuditEventListener listener = new RuntimeAuditEventListener(service);

        listener.onRuntimeEvent(event());

        assertThat(service.list(Criteria.of().eq("eventId", "event-1"), PageRequest.of(1, 10)))
                .singleElement()
                .extracting(RuntimeAuditRecord::getTraceId)
                .isEqualTo("trace-1");
    }

    @Test
    void shouldRejectDuplicateRuntimeEventId() {
        RuntimeEvent event = event();
        service.record(event);

        assertThatThrownBy(() -> service.record(event))
                .hasMessageContaining("Runtime audit eventId must be unique");
    }

    @Test
    void shouldRejectSystemAuditRecordWithoutSystemReason() {
        RuntimeAuditRecord record = new RuntimeAuditRecord();
        record.setEventId("system-event-without-reason");
        record.setTraceId("system-trace-without-reason");
        record.setEventType(RuntimeEventType.AFTER_UPDATE);
        record.setModuleAlias("sales.contract");
        record.setEntityAlias("contract");
        record.setRecordId("contract-1");
        record.setSystemContext(Boolean.FALSE);
        record.setMutationSource(RuntimeMutationSource.SYSTEM);
        record.setOccurredAt(Instant.parse("2026-06-02T04:30:00Z"));

        assertThatThrownBy(() -> service.insert(record))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("systemReason");
    }

    @Test
    void shouldBuildRuntimeAuditQueryCriteriaForActionDimensions() {
        service.record(event());
        service.record(otherActionEvent());

        assertThat(service.list(service.traceCriteria("trace-1"), PageRequest.of(1, 10)))
                .singleElement()
                .extracting(RuntimeAuditRecord::getEventId)
                .isEqualTo("event-1");
        assertThat(service.list(service.actionCriteria("sales.contract", "approve"), PageRequest.of(1, 10)))
                .singleElement()
                .extracting(RuntimeAuditRecord::getActionCode)
                .isEqualTo("approve");
        assertThat(service.list(service.eventTypeCriteria(RuntimeEventType.ACTION_EXECUTED), PageRequest.of(1, 10)))
                .hasSize(2);
        assertThat(service.list(service.resultTypeCriteria("RECORD_ID"), PageRequest.of(1, 10)))
                .singleElement()
                .extracting(RuntimeAuditRecord::getEventId)
                .isEqualTo("event-2");
        assertThat(service.list(service.recordCriteria("sales.contract", "contract", "contract-2"), PageRequest.of(1, 10)))
                .singleElement()
                .extracting(RuntimeAuditRecord::getTraceId)
                .isEqualTo("trace-2");
    }

    @Test
    void shouldExposeRuntimeAuditConsumptionQueries() {
        service.record(event());
        service.record(otherActionEvent());
        service.record(actionFailedEvent());

        assertThat(service.traceEvents("trace-1", PageRequest.of(1, 10)))
                .singleElement()
                .extracting(RuntimeAuditRecord::getEventId)
                .isEqualTo("event-1");
        assertThat(service.recordTimeline("sales.contract", "contract", "contract-1", PageRequest.of(1, 10)))
                .extracting(RuntimeAuditRecord::getEventType)
                .containsExactly(RuntimeEventType.ACTION_EXECUTED, RuntimeEventType.ACTION_FAILED);
        assertThat(service.actionEvents("sales.contract", "submit", PageRequest.of(1, 10)))
                .extracting(RuntimeAuditRecord::getEventId)
                .containsExactly("action-failed-event", "event-2");
        assertThat(service.failedActions("sales.contract", PageRequest.of(1, 10)))
                .singleElement()
                .extracting(RuntimeAuditRecord::getFailureStage)
                .isEqualTo("execute");
        assertThat(service.actionResults("VALUE", PageRequest.of(1, 10)))
                .singleElement()
                .extracting(RuntimeAuditRecord::getEventId)
                .isEqualTo("event-1");
    }

    @Test
    void shouldRejectBlankRuntimeAuditConsumptionQueryKeys() {
        assertThatThrownBy(() -> service.failedActions(" ", PageRequest.of(1, 10)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("moduleAlias must not be blank");
        assertThatThrownBy(() -> service.recordTimeline("sales.contract", " ", "contract-1", PageRequest.of(1, 10)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("entityAlias must not be blank");
        assertThatThrownBy(() -> service.actionResults(" ", PageRequest.of(1, 10)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("resultType must not be blank");
    }

    @Test
    void shouldKeepNullEventTenantEvenWhenTenantContextExists() {
        String id;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-context")) {
            id = service.record(eventWithoutTenant());
        }

        RuntimeAuditRecord record = service.select(id);
        assertThat(record.getTenantId()).isNull();
        assertThat(record.getSystemContext()).isTrue();
        assertThat(record.getSystemReason()).isEqualTo("module bootstrap");
    }

    @Test
    void shouldNotExtractActionResultColumnsFromNonActionEventPayload() {
        String id = service.record(moduleEventWithActionLikePayload());

        RuntimeAuditRecord record = service.select(id);
        assertThat(record.getEventType()).isEqualTo(RuntimeEventType.MODULE_REFRESHED);
        assertThat(record.getResultType()).isNull();
        assertThat(record.getResultMessage()).isNull();
        assertThat(record.getResultText()).isNull();
        assertThat(record.getPayloadText()).contains("resultType=VALUE");
    }

    @Test
    void shouldPersistActionFailurePayloadAsAuditColumns() {
        String id = service.record(actionFailedEvent());

        RuntimeAuditRecord record = service.select(id);
        assertThat(record.getEventType()).isEqualTo(RuntimeEventType.ACTION_FAILED);
        assertThat(record.getExecutorType()).isEqualTo("SERVICE");
        assertThat(record.getActionLevel()).isEqualTo("RECORD");
        assertThat(record.getFailureStage()).isEqualTo("execute");
        assertThat(record.getErrorMessage()).isEqualTo("submit failed");
        assertThat(record.getErrorType()).isEqualTo(IllegalStateException.class.getName());
        assertThat(record.getResultType()).isNull();
        assertThat(record.getResultText()).isNull();
    }

    @Test
    void shouldPersistInteractionOnlyActionAsPayloadSnapshot() {
        String id = service.record(dialogActionEvent());

        RuntimeAuditRecord record = service.select(id);
        assertThat(record.getEventType()).isEqualTo(RuntimeEventType.ACTION_EXECUTED);
        assertThat(record.getExecutorType()).isEqualTo("DIALOG");
        assertThat(record.getActionLevel()).isEqualTo("RECORD");
        assertThat(record.getResultType()).isEqualTo("DIALOG");
        assertThat(record.getResultText()).isNull();
        assertThat(record.getPayloadText()).contains("interactionOnly=true");
    }

    private RuntimeEvent event() {
        return new RuntimeEvent(
                "event-1",
                "trace-1",
                RuntimeEventType.ACTION_EXECUTED,
                "sales.contract",
                "contract",
                "contract-1",
                "approve",
                "tenant-1",
                false,
                "user-1",
                "USER",
                "ROLE_GRANTED",
                "sales.contract:view",
                "view",
                RuntimeMutationSource.ACTION,
                ActionEventPayload.executed("SERVICE", "RECORD", "VALUE", "审批通过",
                        true, "/contracts/contract-1", false, "approved"),
                Instant.parse("2026-06-02T04:00:00Z")
        );
    }

    private RuntimeEvent otherActionEvent() {
        return new RuntimeEvent(
                "event-2",
                "trace-2",
                RuntimeEventType.ACTION_EXECUTED,
                "sales.contract",
                "contract",
                "contract-2",
                "submit",
                "tenant-1",
                false,
                RuntimeMutationSource.ACTION,
                ActionEventPayload.executed("STANDARD", "RECORD", "RECORD_ID", null,
                        false, null, false, "contract-2"),
                Instant.parse("2026-06-02T04:05:00Z")
        );
    }

    private RuntimeEvent eventWithoutTenant() {
        return new RuntimeEvent(
                "event-without-tenant",
                "trace-without-tenant",
                RuntimeEventType.MODULE_REFRESHED,
                "sales.contract",
                null,
                null,
                null,
                null,
                true,
                "module bootstrap",
                RuntimeMutationSource.SYSTEM,
                Map.of(),
                Instant.parse("2026-06-02T04:00:00Z")
        );
    }

    private RuntimeEvent moduleEventWithActionLikePayload() {
        return new RuntimeEvent(
                "module-event-with-action-like-payload",
                "module-trace",
                RuntimeEventType.MODULE_REFRESHED,
                "sales.contract",
                null,
                null,
                null,
                "tenant-1",
                true,
                "module publication",
                RuntimeMutationSource.SYSTEM,
                Map.of("resultType", "VALUE", "message", "发布完成", "result", "ok"),
                Instant.parse("2026-06-02T04:10:00Z")
        );
    }

    private RuntimeEvent actionFailedEvent() {
        return new RuntimeEvent(
                "action-failed-event",
                "action-failed-trace",
                RuntimeEventType.ACTION_FAILED,
                "sales.contract",
                "contract",
                "contract-1",
                "submit",
                "tenant-1",
                false,
                RuntimeMutationSource.ACTION,
                Map.of(
                        "executorType", "SERVICE",
                        "actionLevel", "RECORD",
                        "available", true,
                        "failureStage", "execute",
                        "errorMessage", "submit failed",
                        "errorType", IllegalStateException.class.getName()
                ),
                Instant.parse("2026-06-02T04:15:00Z")
        );
    }

    private RuntimeEvent dialogActionEvent() {
        return new RuntimeEvent(
                "dialog-action-event",
                "dialog-trace",
                RuntimeEventType.ACTION_EXECUTED,
                "sales.contract",
                "contract",
                "contract-1",
                "submitDialog",
                "tenant-1",
                false,
                RuntimeMutationSource.ACTION,
                ActionEventPayload.executed("DIALOG", "RECORD", "DIALOG", null,
                        false, null, true, null),
                Instant.parse("2026-06-02T04:20:00Z")
        );
    }
}
