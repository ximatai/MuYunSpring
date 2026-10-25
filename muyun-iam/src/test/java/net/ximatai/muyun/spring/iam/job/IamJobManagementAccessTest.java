package net.ximatai.muyun.spring.iam.job;

import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.iam.role.RoleService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IamJobManagementAccessTest {
    @Test
    void onlyOwningTenantAdministratorOrPlatformAccountMayManage() {
        var roles = mock(RoleService.class);
        when(roles.hasTenantAdministratorAccess("admin", "tenant-a")).thenReturn(true);
        var access = new IamJobManagementAccess(roles);
        assertThatThrownBy(() -> access.requireAdministrator("tenant-a"))
                .isInstanceOf(PlatformAccessDeniedException.class);
        try (var ignored = CurrentUserContext.use(CurrentUser.tenantUser("admin", "Admin", "tenant-a"))) {
            access.requireAdministrator("tenant-a");
            assertThatThrownBy(() -> access.requireAdministrator("tenant-b"))
                    .isInstanceOf(PlatformAccessDeniedException.class);
            assertThatThrownBy(() -> access.requireAdministrator(null))
                    .isInstanceOf(PlatformAccessDeniedException.class);
        }
        try (var ignored = CurrentUserContext.use(CurrentUser.tenantUser("member", "Member", "tenant-a"))) {
            assertThatThrownBy(() -> access.requireAdministrator("tenant-a"))
                    .isInstanceOf(PlatformAccessDeniedException.class);
        }
        try (var ignored = CurrentUserContext.use(CurrentUser.systemUser("platform", "Platform"))) {
            access.requireAdministrator(null);
            access.requireAdministrator("tenant-b");
        }
        try (var ignored = CurrentUserContext.use(CurrentUser.systemUser("platform", "Platform", true))) {
            assertThatThrownBy(() -> access.requireAdministrator(null))
                    .isInstanceOf(PlatformAccessDeniedException.class);
        }
    }
}
