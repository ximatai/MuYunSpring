package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobSystemActionPolicyTest {
    @Test void systemJobsCannotBypassWhitelistThroughAnonymousLoginOrSystemAccountPolicies() {
        var roles = mock(RoleService.class); var gate = mock(JobSystemActionPolicy.class);
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("gate", gate);
        var service = new RoleActionExecutionPolicyService(roles); service.jobSystemPolicies(beans.getBeanProvider(JobSystemActionPolicy.class));
        try (var tenant = TenantContext.tenantSystem("tenant-a", "platform.module-action")) {
            for (var mode : ActionAccessMode.values()) {
                var policy = new ActionExecutionPolicy("execute", PlatformActionLevel.LIST, mode, false, false, null, null);
                var context = ActionExecutionContext.ofPolicy("test.target", policy, Set.of(), Optional.of(CurrentUser.systemUser("spoof", "spoof")));
                assertThatThrownBy(() -> service.authorize(context)).hasMessageContaining("whitelisted");
                when(gate.allows("test.target", "execute")).thenReturn(true);
                var allowed = service.authorize(context);
                assertThat(allowed.decision()).isEqualTo(JobSystemActionPolicy.DECISION);
                assertThat(allowed.operatorType()).isEqualTo("BACKGROUND_JOB");
                when(gate.allows("test.target", "execute")).thenReturn(false);
            }
        }
        verifyNoInteractions(roles);
    }
    @Test void whitelistManagementRemainsPlatformOnlyEvenWithAnonymousGovernanceOverride() {
        var service = new RoleActionExecutionPolicyService(mock(RoleService.class));
        var policy = new ActionExecutionPolicy("query", PlatformActionLevel.LIST, ActionAccessMode.ANONYMOUS_ALLOWED, false, false, null, null);
        for (var user : List.of(Optional.<CurrentUser>empty(), Optional.of(CurrentUser.tenantUser("user", "user", "tenant-a")))) {
            var context = ActionExecutionContext.ofPolicy("platform.system_action_whitelist", policy, Set.of(), user);
            assertThatThrownBy(() -> service.authorize(context)).hasMessageContaining("platform administrator");
        }
    }
    @Test void whitelistedSystemDataScopeNeverProducesCrossTenantBypass() {
        var gate = mock(JobSystemActionPolicy.class); var beans = new DefaultListableBeanFactory(); beans.registerSingleton("gate", gate);
        var scope = new RoleDataScopeCriteriaService(mock(RoleService.class), mock(TenantAdminImplicitGrantPolicy.class), Optional.empty(), Optional.empty(), Optional.empty());
        scope.jobSystemPolicies(beans.getBeanProvider(JobSystemActionPolicy.class));
        try (var tenant = TenantContext.tenantSystem("tenant-a", "platform.module-action")) {
            var policy = ActionExecutionContext.ofActionCode("test.target", "execute", Set.of(), Optional.empty()).actionPolicy();
            var denied = scope.resolveReadScope("test.target", policy, null, Optional.empty());
            assertThat(denied.criteria().isEmpty()).isFalse();
            when(gate.allows("test.target", "execute")).thenReturn(true);
            var allowed = scope.resolveReadScope("test.target", policy, null, Optional.empty());
            assertThat(allowed.crossTenant()).isFalse(); assertThat(allowed.criteria().isEmpty()).isTrue();
            assertThat(TenantContext.currentTenantId()).contains("tenant-a"); assertThat(TenantContext.tenantFilterBypassed()).isFalse();
        }
    }
}
