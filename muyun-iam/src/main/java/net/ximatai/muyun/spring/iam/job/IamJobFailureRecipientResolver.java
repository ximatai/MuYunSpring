package net.ximatai.muyun.spring.iam.job;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.role.RoleService;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import net.ximatai.muyun.spring.platform.job.JobFailureRecipientResolver;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationRecipients;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class IamJobFailureRecipientResolver implements JobFailureRecipientResolver {
    private final UserAccountService users;
    private final RoleService roles;

    public IamJobFailureRecipientResolver(UserAccountService users, RoleService roles) {
        this.users = users;
        this.roles = roles;
    }

    @Override
    public BusinessNotificationRecipients resolve(String tenantId) {
        try (var user = CurrentUserContext.without();
             var system = TenantContext.system("resolve background job failure administrators");
             var bypass = TenantContext.bypassTenantFilter("resolve administrators in the exact job owning scope")) {
            List<String> recipients = users.list(Criteria.of().eqNullable("tenantId", tenantId).eq("enabled", true))
                    .stream()
                    .filter(account -> Objects.equals(tenantId, account.getTenantId()))
                    .filter(account -> Boolean.TRUE.equals(account.getEnabled()))
                    .filter(account -> tenantId == null || roles.hasTenantAdministratorAccess(account.getId(), tenantId))
                    .map(UserAccount::getId)
                    .toList();
            return new BusinessNotificationRecipients(false, List.of(), List.of(), List.of(), List.of(), recipients);
        }
    }
}
