package net.ximatai.muyun.spring.platform.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.platform.JobSystemActionPolicy;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.module.ModuleActionInvoker;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScheduledActionPlanServiceTest {
    private final ScheduledActionPlanDao rows = mock(ScheduledActionPlanDao.class);
    private final JobService jobs = mock(JobService.class);
    private final ModuleActionInvoker actions = mock(ModuleActionInvoker.class);
    private final TrustedExecutionIdentityResolver identities = mock(TrustedExecutionIdentityResolver.class);
    private final JobSystemActionPolicy whitelist = mock(JobSystemActionPolicy.class);
    private final net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier tenants = mock(net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier.class);
    private ScheduledActionPlanService service;
    @BeforeEach void setup() {
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("identities", identities);
        service = new ScheduledActionPlanService(rows, jobs, actions, beans.getBeanProvider(TrustedExecutionIdentityResolver.class),
                whitelist, tenant -> { }, new ObjectMapper().findAndRegisterModules(), tenants);
        when(jobs.registerManaged(anyString(), any(), anyMap())).thenAnswer(call -> { JobPlan plan = new JobPlan(); plan.setId("job-plan"); return plan; });
        when(identities.capture("test.target", "execute")).thenAnswer(call -> Optional.of(new TrustedExecutionIdentityResolver.Reference(
                CurrentUserContext.currentUser().orElseThrow().userId(), "tenant-a", Instant.EPOCH, null)));
    }
    @Test void ordinarySmallPageBuildsVisibilityWithoutReadingPlansOrParsingArguments() {
        service = new ScheduledActionPlanService(rows, jobs, actions,
                new DefaultListableBeanFactory().getBeanProvider(TrustedExecutionIdentityResolver.class),
                whitelist, tenant -> { throw new net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException("ordinary user"); },
                new ObjectMapper(), tenants);
        try (var user = CurrentUserContext.use(CurrentUser.tenantUser("reader", "reader", "tenant-a"));
             var tenant = TenantContext.use("tenant-a")) {
            service.activeCriteria(net.ximatai.muyun.database.core.orm.Criteria.of().eq("enabled", true));
        }
        verifyNoInteractions(rows);
        verify(actions, never()).requireAuthorized(anyString(), anyString(), anyMap());
    }
    @Test void savingCapturesLastEditorAndDoesNotTrustSubmittedIdentityFields() {
        var row = row(); row.setExecutionUserId("spoofed"); row.setJobPlanId("spoofed");
        try (var user = CurrentUserContext.use(CurrentUser.tenantUser("first", "first", "tenant-a")); var tenant = TenantContext.use("tenant-a")) { service.beforeInsert(row); }
        assertThat(row.getExecutionUserId()).isEqualTo("first"); assertThat(row.getJobPlanId()).isEqualTo("job-plan");
        when(rows.findById("topic-plan")).thenReturn(row);
        try (var user = CurrentUserContext.use(CurrentUser.tenantUser("second", "second", "tenant-a")); var tenant = TenantContext.use("tenant-a")) { service.beforeUpdate(row); }
        assertThat(row.getExecutionUserId()).isEqualTo("second");
        verify(actions, times(2)).requireAuthorized("test.target", "execute", Map.of());
    }
    @Test void updateRestoresOmittedTenantAndManagedPlanButRejectsExplicitReassignment() {
        var old = row(); old.setJobPlanId("persisted-plan"); old.setVersion(3);
        when(rows.query(any(net.ximatai.muyun.database.core.orm.Criteria.class), any(net.ximatai.muyun.database.core.orm.PageRequest.class))).thenReturn(List.of(old));
        when(rows.updateByIdAndVersion(any(), eq(3))).thenReturn(1);
        when(rows.findById(old.getId())).thenReturn(old);
        try (var user = CurrentUserContext.use(CurrentUser.tenantUser("editor", "editor", "tenant-a"));
             var tenant = TenantContext.use("tenant-a")) {
            var incoming = row(); incoming.setTenantId(null); incoming.setJobPlanId(null); incoming.setVersion(3);
            assertThat(service.update(incoming)).isEqualTo(1);
            assertThat(incoming.getTenantId()).isEqualTo("tenant-a");
            var captured = org.mockito.ArgumentCaptor.forClass(JobPlan.class);
            verify(jobs).registerManaged(eq(ScheduledActionPlanService.MODULE_ALIAS), captured.capture(), anyMap());
            assertThat(captured.getValue().getId()).isEqualTo("persisted-plan");
            assertThat(incoming.getExecutionUserId()).isEqualTo("editor");
            var forged = row(); forged.setExecutionTenantId("tenant-b");
            assertThatThrownBy(() -> service.beforeUpdate(forged)).hasMessageContaining("account tenant");
        }
    }
    @Test void platformAccountAlwaysUsesWhitelistedSystemMode() {
        var row = row();
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("admin", "admin")); var tenant = TenantContext.system("test")) {
            assertThatThrownBy(() -> service.beforeInsert(row)).hasMessageContaining("whitelisted");
            when(whitelist.allows("test.target", "execute")).thenReturn(true); service.beforeInsert(row);
        }
        assertThat(row.getIdentityMode()).isEqualTo("SYSTEM"); assertThat(row.getExecutionUserId()).isNull(); verifyNoInteractions(identities);
    }
    @Test void platformConfigurationValidatesInThePlansBusinessTenantAndRestoresAmbientScope() {
        when(whitelist.allows("test.target", "execute")).thenReturn(true);
        when(actions.validateTarget("test.target", "execute")).thenAnswer(call -> {
            assertThat(TenantContext.currentTenantId()).contains("tenant-a"); return null;
        });
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("admin", "admin")); var tenant = TenantContext.system("test")) {
            service.beforeInsert(row()); assertThat(TenantContext.isSystem()).isTrue();
        }
    }
    @Test void rejectsCrossTenantSystemRequestsAndUnresolvableInitiators() {
        try (var user = CurrentUserContext.use(CurrentUser.tenantUser("user", "user", "tenant-a")); var tenant = TenantContext.use("tenant-a")) {
            var cross = row(); cross.setExecutionTenantId("tenant-b"); assertThatThrownBy(() -> service.beforeInsert(cross)).hasMessageContaining("tenant");
            var system = row(); system.setIdentityMode("SYSTEM"); assertThatThrownBy(() -> service.beforeInsert(system)).hasMessageContaining("administrator");
            when(identities.capture(anyString(), anyString())).thenReturn(Optional.empty()); assertThatThrownBy(() -> service.beforeInsert(row())).hasMessageContaining("Initiator");
        }
        verifyNoInteractions(jobs);
    }
    @Test void rejectsMissingOrInactiveExecutionTenantsBeforeRegisteringAJob() {
        doThrow(new IllegalArgumentException("Inactive tenant")).when(tenants).verifyActiveTenant("missing");
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("admin", "admin")); var tenant = TenantContext.system("test")) {
            var row = row(); row.setExecutionTenantId("missing");
            assertThatThrownBy(() -> service.beforeInsert(row)).hasMessageContaining("Inactive tenant");
        }
        verifyNoInteractions(jobs);
    }
    @Test void initiatorWithoutSubmittedScopeIsFixedToAccountTenant() {
        try (var user = CurrentUserContext.use(CurrentUser.tenantUser("user", "user", "tenant-a")); var tenant = TenantContext.use("tenant-a")) {
            var row = row(); row.setExecutionTenantId(null); row.setTenantId(null);
            service.beforeInsert(row);
            assertThat(row.getExecutionTenantId()).isEqualTo("tenant-a");
            assertThat(row.getTenantId()).isEqualTo("tenant-a");
        }
    }
    @Test void systemOwnershipFieldIsNotAnExecutionScopeInputAndWhitelistDenialHasATextSource() {
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("admin", "admin")); var tenant = TenantContext.system("test")) {
            var row = row(); row.setExecutionTenantId(null); // tenantId still carries an untrusted submitted value.
            assertThatThrownBy(() -> service.beforeInsert(row)).isInstanceOfSatisfying(
                    net.ximatai.muyun.spring.common.exception.PlatformException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("ACCESS_DENIED");
                        assertThat(failure.textSource().sourceId()).contains("system-action-not-whitelisted");
                    });
            when(whitelist.allows("test.target", "execute")).thenReturn(true);
            service.beforeInsert(row);
            assertThat(row.getTenantId()).isNull();
            verify(tenants, never()).verifyActiveTenant(anyString());
        }
    }
    @Test void updatesRequireTheExpectedRecordVersion() throws Exception {
        var omitted = new ObjectMapper().readValue("{}", ScheduledActionPlan.class);
        var initial = row(); initial.setVersion(0);
        assertThatThrownBy(() -> service.expectedVersionForUpdate(omitted, initial))
                .isInstanceOf(net.ximatai.muyun.spring.ability.OptimisticLockException.class);
        var existing = row(); existing.setVersion(3);
        var incoming = row();
        assertThatThrownBy(() -> service.expectedVersionForUpdate(incoming, existing))
                .isInstanceOf(net.ximatai.muyun.spring.ability.OptimisticLockException.class);
        incoming.setVersion(2);
        assertThatThrownBy(() -> service.expectedVersionForUpdate(incoming, existing))
                .isInstanceOf(net.ximatai.muyun.spring.ability.OptimisticLockException.class);
        incoming.setVersion(3);
        assertThat(service.expectedVersionForUpdate(incoming, existing)).isEqualTo(3);
        verifyNoInteractions(jobs);
    }
    @Test void boundsAndCredentialChecksApplyRecursively() {
        assertThatThrownBy(() -> ScheduledActionPlanService.arguments("{\"nested\":[{\"access_token\":\"secret\"}]}" )).hasMessageContaining("Credentials");
        assertThatThrownBy(() -> ScheduledActionPlanService.arguments("{\"value\":\"" + "x".repeat(16384) + "\"}")).hasMessageContaining("16 KiB");
        assertThatThrownBy(() -> ScheduledActionPlanService.arguments("[]")).hasMessageContaining("Invalid frozen");
        assertThatThrownBy(() -> ScheduledActionPlanService.arguments("{\"providerApiKey\":\"secret\"}")).hasMessageContaining("Credentials");
        assertThatThrownBy(() -> ScheduledActionPlanService.arguments("{\"authorizationHeader\":\"secret\"}")).hasMessageContaining("Credentials");
    }
    private ScheduledActionPlan row() { var row = new ScheduledActionPlan(); row.setId("topic-plan"); row.setTenantId("tenant-a"); row.setExecutionTenantId("tenant-a"); row.setModuleAlias("test.target"); row.setActionCode("execute"); return row; }
}
