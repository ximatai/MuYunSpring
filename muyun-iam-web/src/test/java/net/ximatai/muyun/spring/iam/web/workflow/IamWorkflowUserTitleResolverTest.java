package net.ximatai.muyun.spring.iam.web.workflow;

import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import net.ximatai.muyun.spring.common.tenant.TenantContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IamWorkflowUserTitleResolverTest {
    @AfterEach void cleanup() { TenantContext.clear(); }
    @Test
    void resolvesPlatformActorsButDoesNotExposeAnotherTenantsAccountTitle() {
        TenantContext.setTenantId("current");
        var users = mock(UserAccountService.class);
        var other = new UserAccount();
        other.setTenantId("other"); other.setTitle("其他租户人员");
        var platform = new UserAccount(); platform.setTitle("平台管理员");
        when(users.select("other")).thenReturn(other);
        when(users.select("platform")).thenReturn(platform);
        assertThat(new IamWorkflowUserTitleResolver(users).titles(List.of("other", "platform")))
                .containsOnlyKeys("platform").containsEntry("platform", "平台管理员");
        assertThat(TenantContext.currentTenantId()).contains("current");
    }
    @Test
    void displaysAccountNameWhenNoDisplayTitleWasConfiguredAndSkipsMissingAccounts() {
        var users = mock(UserAccountService.class);
        var named = new UserAccount();
        named.setTitle("张老师");
        named.setUsername("teacher");
        var unnamed = new UserAccount();
        unnamed.setTitle(" ");
        unnamed.setUsername("tenant_admin");
        when(users.select("named")).thenReturn(named);
        when(users.select("unnamed")).thenReturn(unnamed);
        var resolver = new IamWorkflowUserTitleResolver(users);
        assertThat(resolver.titles(List.of("named", "unnamed", "missing", "named")))
                .containsEntry("named", "张老师").containsEntry("unnamed", "tenant_admin")
                .doesNotContainKey("missing");
        verify(users, times(1)).select("named");
    }
}
