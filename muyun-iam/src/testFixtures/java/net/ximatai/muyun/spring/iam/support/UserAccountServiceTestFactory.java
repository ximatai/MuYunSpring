package net.ximatai.muyun.spring.iam.support;

import net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.iam.role.AccountRoleGrantDao;
import net.ximatai.muyun.spring.iam.user.PasswordHashingService;
import net.ximatai.muyun.spring.iam.user.PasswordPolicyRuleService;
import net.ximatai.muyun.spring.iam.user.PasswordSecurityPolicyResolver;
import net.ximatai.muyun.spring.iam.user.PasswordSecurityPolicyService;
import net.ximatai.muyun.spring.iam.user.PasswordSecurityPolicyDao;
import net.ximatai.muyun.spring.iam.user.LoginLockoutPolicy;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import net.ximatai.muyun.spring.iam.user.UserAccountDao;
import net.ximatai.muyun.spring.iam.user.UserAccountSecurityServices;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import net.ximatai.muyun.spring.iam.user.UserSecurityEventPublisher;
import net.ximatai.muyun.spring.iam.user.UserSessionRevocationService;
import net.ximatai.muyun.spring.iam.user.UserSessionPresenceService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class UserAccountServiceTestFactory {
    private UserAccountServiceTestFactory() {
    }

    /**
     * A resolver with the same persisted platform default produced by initial data.
     */
    public static PasswordSecurityPolicyResolver defaultResolver() {
        return defaultResolver(LoginLockoutPolicy.defaults());
    }

    public static PasswordSecurityPolicyResolver defaultResolver(LoginLockoutPolicy lockout) {
        return defaultResolver(lockout, mock(PasswordPolicyRuleService.class));
    }

    public static PasswordSecurityPolicyResolver defaultResolver(LoginLockoutPolicy lockout,
                                                                  PasswordPolicyRuleService rules) {
        var stored = new PasswordSecurityPolicyService(mock(PasswordSecurityPolicyDao.class), mock(TenantService.class), rules)
                .initialData().getFirst();
        stored.setMaxFailedAttempts(lockout.maxFailedAttempts());
        stored.setLockDurationSeconds(lockout.lockDuration() == null ? null : (int) lockout.lockDuration().toSeconds());
        var policies = mock(PasswordSecurityPolicyService.class);
        when(policies.findPlatformPolicy()).thenReturn(java.util.Optional.of(stored));
        return new PasswordSecurityPolicyResolver(policies, rules);
    }

    public static UserAccountService create(UserAccountDao dao,
                                            ActiveTenantVerifier activeTenantVerifier,
                                            PasswordHashingService passwordHashingService) {
        return create(dao, activeTenantVerifier, passwordHashingService, LoginLockoutPolicy.defaults());
    }

    public static UserAccountService create(UserAccountDao dao, ActiveTenantVerifier activeTenantVerifier,
                                            PasswordHashingService passwordHashingService, LoginLockoutPolicy lockout) {
        return new UserAccountService(
                dao,
                activeTenantVerifier,
                passwordHashingService,
                mock(AccountRoleGrantDao.class),
                new UserAccountSecurityServices(
                        defaultResolver(lockout),
                        UserSecurityEventPublisher.NOOP,
                        mock(UserSessionRevocationService.class),
                        mock(UserSessionPresenceService.class),
                        mock(net.ximatai.muyun.spring.iam.user.PersonalAccessTokenStore.class))) {
            @Override
            public net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService getDataScopeCriteriaService() {
                return new AllowAllDataScopeCriteriaService();
            }
        };
    }
}
