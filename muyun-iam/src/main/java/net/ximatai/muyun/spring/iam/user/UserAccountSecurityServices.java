package net.ximatai.muyun.spring.iam.user;

import java.util.Objects;

/**
 * Required security collaborators of {@link UserAccountService}. The password policy resolver is a
 * mandatory dependency: password complexity and login-lockout decisions must always go through the
 * single effective-policy entry, never a silently disabled fallback.
 */
public record UserAccountSecurityServices(
        PasswordSecurityPolicyResolver passwordSecurityPolicyResolver,
        UserSecurityEventPublisher securityEventPublisher,
        UserSessionRevocationService sessionRevocationService,
        UserSessionPresenceService sessionPresenceService,
        PersonalAccessTokenStore personalAccessTokenStore
) {
    public UserAccountSecurityServices {
        Objects.requireNonNull(passwordSecurityPolicyResolver, "passwordSecurityPolicyResolver");
        Objects.requireNonNull(securityEventPublisher, "securityEventPublisher");
        Objects.requireNonNull(sessionRevocationService, "sessionRevocationService");
        Objects.requireNonNull(sessionPresenceService, "sessionPresenceService");
        Objects.requireNonNull(personalAccessTokenStore, "personalAccessTokenStore");
    }
}
