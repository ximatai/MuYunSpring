package net.ximatai.muyun.spring.platform.job;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.exception.AuthenticationRequiredException;

final class JobManagementQueryScope {
    private JobManagementQueryScope() {
    }

    static Criteria apply(Criteria criteria, JobManagementAccess access) {
        var user = CurrentUserContext.currentUser()
                .orElseThrow(() -> new AuthenticationRequiredException("job management requires an administrator"));
        access.requireAdministrator(user.system() ? null : user.tenantId());
        Criteria scoped = Criteria.of();
        if (criteria != null && !criteria.isEmpty()) scoped.andGroup(criteria.getRoot());
        if (!user.system()) scoped.eq("tenantId", user.tenantId());
        return scoped;
    }
}
