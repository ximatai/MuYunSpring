package net.ximatai.muyun.spring.iam.support;

import net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.iam.role.AccountRoleGrantDao;
import net.ximatai.muyun.spring.iam.user.PasswordHashingService;
import net.ximatai.muyun.spring.iam.user.UserAccountAuthorizationServices;
import net.ximatai.muyun.spring.iam.user.UserAccountDao;
import net.ximatai.muyun.spring.iam.user.UserAccountSecurityServices;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import net.ximatai.muyun.spring.iam.user.UserSecurityEventPublisher;
import net.ximatai.muyun.spring.iam.user.UserSessionRevocationService;
import net.ximatai.muyun.spring.iam.user.UserSessionPresenceService;

import java.util.Optional;

import static org.mockito.Mockito.mock;

public final class UserAccountServiceTestFactory {
    private UserAccountServiceTestFactory() {
    }

    public static UserAccountService create(UserAccountDao dao,
                                            ActiveTenantVerifier activeTenantVerifier,
                                            PasswordHashingService passwordHashingService) {
        return new UserAccountService(
                dao,
                activeTenantVerifier,
                passwordHashingService,
                new UserAccountAuthorizationServices(
                        AllowAllDataScopeCriteriaService::new,
                        mock(AccountRoleGrantDao.class)),
                new UserAccountSecurityServices(
                        Optional.empty(),
                        UserSecurityEventPublisher.NOOP,
                        mock(UserSessionRevocationService.class),
                        mock(UserSessionPresenceService.class)));
    }
}
