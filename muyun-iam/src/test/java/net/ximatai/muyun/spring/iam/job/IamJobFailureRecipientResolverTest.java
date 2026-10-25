package net.ximatai.muyun.spring.iam.job;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaClause;
import net.ximatai.muyun.database.core.orm.CriteriaOperator;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.role.RoleService;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class IamJobFailureRecipientResolverTest {
    @Test
    void resolvesEnabledExactScopeAdministratorsAndRestoresTenant() {
        var users = mock(UserAccountService.class);
        var roles = mock(RoleService.class);
        when(users.list(any(Criteria.class))).thenAnswer(call -> {
            assertThat(TenantContext.isSystem()).isTrue();
            assertThat(TenantContext.tenantFilterBypassed()).isTrue();
            Criteria criteria = call.getArgument(0);
            CriteriaClause ownership = criteria.getClauses().stream()
                    .filter(clause -> "tenantId".equals(clause.getField())).findFirst().orElseThrow();
            assertThat(ownership.getOperator()).isIn(CriteriaOperator.EQ, CriteriaOperator.IS_NULL);
            if (ownership.getOperator() == CriteriaOperator.EQ) {
                assertThat(ownership.getValues()).containsExactly("tenant-a");
            }
            return List.of(user("admin", "tenant-a", true), user("member", "tenant-a", true),
                    user("disabled", "tenant-a", false), user("foreign", "tenant-b", true),
                    user("platform", null, true));
        });
        when(roles.hasTenantAdministratorAccess("admin", "tenant-a")).thenReturn(true);
        when(roles.hasTenantAdministratorAccess("disabled", "tenant-a")).thenReturn(true);
        var resolver = new IamJobFailureRecipientResolver(users, roles);
        try (var ignored = TenantContext.tenantSystem("tenant-a", "test.sync")) {
            assertThat(resolver.resolve("tenant-a").userIds()).containsExactly("admin");
            assertThat(resolver.resolve(null).userIds()).containsExactly("platform");
            assertThat(TenantContext.currentTenantId()).contains("tenant-a");
            assertThat(TenantContext.backgroundJobType()).contains("test.sync");
            assertThat(TenantContext.tenantFilterBypassed()).isFalse();
        }
        verify(roles).hasTenantAdministratorAccess("admin", "tenant-a");
        verify(roles).hasTenantAdministratorAccess("member", "tenant-a");
        verify(roles, never()).hasTenantAdministratorAccess("foreign", "tenant-a");
        verify(roles, never()).hasTenantAdministratorAccess("platform", "tenant-a");
    }

    private UserAccount user(String id, String tenant, boolean enabled) {
        var user = new UserAccount();
        user.setId(id);
        user.setTenantId(tenant);
        user.setEnabled(enabled);
        return user;
    }
}
