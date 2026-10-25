package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.event.RuntimeEvent;
import net.ximatai.muyun.spring.ability.event.RuntimeEventType;
import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantSystemExecutionContractTest {
    @Test
    void tenantSystemCannotReadOrMutateForeignRecordsThroughStandardCrud() {
        var dao = new InMemoryBaseDao<DemoPlainRecord>();
        var service = new TenantActiveScopedService<DemoPlainRecord>(
                "test.records", DemoPlainRecord.class, dao, tenant -> {}) {};
        var foreign = new DemoPlainRecord("Foreign");
        foreign.setId("foreign");
        foreign.setTenantId("tenant-b");
        foreign.setVersion(0);
        dao.insert(foreign);
        try (var job = TenantContext.tenantSystem("tenant-a", "test.sync")) {
            assertThat(service.select("foreign")).isNull();
            assertThat(service.list(Criteria.of())).isEmpty();
            assertThat(service.update(foreign)).isZero();
            assertThat(service.delete("foreign", 0)).isZero();
            assertThatThrownBy(() -> service.insert(newForeignRecord()))
                    .isInstanceOf(PlatformAccessDeniedException.class);
            var owned = new DemoPlainRecord("Owned");
            service.insert(owned);
            assertThat(service.select(owned.getId()).getTenantId()).isEqualTo("tenant-a");
            assertThat(owned.getCreatedBy()).isEqualTo("job:XoGoQyYmed5ePuijfX0ozKToCc49PbFr6wPxQL9MWAw");
            var changed = new DemoPlainRecord("Changed");
            changed.setId(owned.getId());
            changed.setVersion(owned.getVersion());
            changed.setTenantId("tenant-b");
            assertThat(service.update(changed)).isEqualTo(1);
            assertThat(changed.getTenantId()).isEqualTo("tenant-a");
            assertThat(TenantContext.isSystem()).isFalse();
        }
        assertThat(dao.findById("foreign").getTitle()).isEqualTo("Foreign");
    }

    @Test
    void runtimeEventRecordsTenantSystemActorAndRequiredReason() {
        try (var job = TenantContext.tenantSystem("tenant-a", "test.sync")) {
            var event = RuntimeEvent.of(RuntimeEventType.AFTER_UPDATE, "test.records", "record", "id", null,
                    "wrong-tenant", false, RuntimeMutationSource.BUSINESS, Map.of());
            assertThat(event.tenantId()).isEqualTo("tenant-a");
            assertThat(event.systemContext()).isTrue();
            assertThat(event.systemReason()).isEqualTo("后台作业 test.sync");
            assertThat(event.operatorId()).isEqualTo("job:XoGoQyYmed5ePuijfX0ozKToCc49PbFr6wPxQL9MWAw");
            assertThat(event.operatorType()).isEqualTo("BACKGROUND_JOB");
        }
    }

    @Test
    void platformJobRetainsTheBusinessEventsTenantWithoutBecomingATenantSubject() {
        try (var job = TenantContext.backgroundJob("test.cleanup")) {
            var event = RuntimeEvent.of(RuntimeEventType.AFTER_DELETE, "test.records", "record", "id", null,
                    "tenant-b", true, RuntimeMutationSource.SYSTEM, Map.of());
            assertThat(event.tenantId()).isEqualTo("tenant-b");
            assertThat(event.operatorType()).isEqualTo("BACKGROUND_JOB");
            assertThat(event.systemReason()).isEqualTo("后台作业 test.cleanup");
            assertThat(TenantContext.currentTenantId()).isEmpty(); assertThat(TenantContext.isSystem()).isTrue();
        }
    }

    @Test
    void jobAuditCorrelationIsFrozenAndIndependentOfActingContext() {
        var reference = new net.ximatai.muyun.spring.common.identity.JobExecutionContext.Reference(
                "test.sync", "plan-1", "run-1", "SYSTEM");
        RuntimeEvent event;
        try (var job = net.ximatai.muyun.spring.common.identity.JobExecutionContext.use(reference);
             var tenant = TenantContext.tenantSystem("tenant-a", "test.sync");
             var trace = net.ximatai.muyun.spring.common.web.RequestTraceContext.use("job-trace")) {
            event = RuntimeEvent.of(RuntimeEventType.AFTER_UPDATE, "test.records", "record", "id", null,
                    "tenant-a", false, RuntimeMutationSource.BUSINESS, Map.of());
            assertThat(event.traceId()).isEqualTo("job-trace");
            var actor = net.ximatai.muyun.spring.common.identity.CurrentUser.tenantUser("operator", "Operator", "tenant-a");
            try (var acting = net.ximatai.muyun.spring.common.identity.ActingContextHolder.use(
                    new net.ximatai.muyun.spring.common.identity.ActingContext("delegation", actor,
                            net.ximatai.muyun.spring.common.identity.BusinessPrincipal.userAccount("principal"), null, null))) {
                var captured = net.ximatai.muyun.spring.ability.event.RuntimeEventAuditContext.capture("test.records", null);
                assertThat(captured).containsEntry("jobRunId", "run-1").containsEntry("actingDelegationId", "delegation");
            }
        }
        assertThat(net.ximatai.muyun.spring.common.identity.JobExecutionContext.current()).isEmpty();
        assertThat(event.auditContext()).containsEntry("jobType", "test.sync").containsEntry("jobPlanId", "plan-1")
                .containsEntry("jobRunId", "run-1").containsEntry("jobIdentityMode", "SYSTEM");
    }

    private DemoPlainRecord newForeignRecord() {
        var foreign = new DemoPlainRecord("Foreign");
        foreign.setTenantId("tenant-b");
        return foreign;
    }
}
