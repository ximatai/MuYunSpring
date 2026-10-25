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
    private ScheduledActionPlanService service;
    @BeforeEach void setup() {
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("identities", identities);
        service = new ScheduledActionPlanService(rows, jobs, actions, beans.getBeanProvider(TrustedExecutionIdentityResolver.class),
                whitelist, tenant -> { }, new ObjectMapper().findAndRegisterModules());
        when(jobs.registerManaged(anyString(), any(), anyMap())).thenAnswer(call -> { JobPlan plan = call.getArgument(1); plan.setId("job-plan"); return plan; });
        when(identities.capture("test.target", "execute")).thenAnswer(call -> Optional.of(new TrustedExecutionIdentityResolver.Reference(
                CurrentUserContext.currentUser().orElseThrow().userId(), "tenant-a", Instant.EPOCH, null)));
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
            var cross = row(); cross.setTenantId("tenant-b"); assertThatThrownBy(() -> service.beforeInsert(cross)).hasMessageContaining("tenant");
            var system = row(); system.setIdentityMode("SYSTEM"); assertThatThrownBy(() -> service.beforeInsert(system)).hasMessageContaining("administrator");
            when(identities.capture(anyString(), anyString())).thenReturn(Optional.empty()); assertThatThrownBy(() -> service.beforeInsert(row())).hasMessageContaining("Initiator");
        }
        verifyNoInteractions(jobs);
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
    private ScheduledActionPlan row() { var row = new ScheduledActionPlan(); row.setId("topic-plan"); row.setTenantId("tenant-a"); row.setModuleAlias("test.target"); row.setActionCode("execute"); return row; }
}
