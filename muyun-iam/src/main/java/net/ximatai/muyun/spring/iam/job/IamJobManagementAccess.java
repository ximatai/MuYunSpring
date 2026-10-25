package net.ximatai.muyun.spring.iam.job;

import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.iam.role.RoleService;
import net.ximatai.muyun.spring.platform.job.JobManagementAccess;
import org.springframework.stereotype.Service;

@Service
public class IamJobManagementAccess implements JobManagementAccess {
    private final RoleService roles;

    public IamJobManagementAccess(RoleService roles) {
        this.roles = roles;
    }

    @Override
    public void requireAdministrator(String tenantId) {
        var user = CurrentUserContext.currentUser().orElse(null);
        if (user != null && !user.passwordChangeRequired()) {
            if (user.system()) return;
            if (tenantId != null && tenantId.equals(user.tenantId())
                    && roles.hasTenantAdministratorAccess(user.userId(), tenantId)) return;
        }
        throw new PlatformAccessDeniedException("job management requires an administrator of the owning scope");
    }
}
