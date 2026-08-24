package net.ximatai.muyun.spring.iam.user;

import java.util.Objects;
import java.util.Optional;

public record UserAccountSecurityServices(
        Optional<PasswordPolicyRuleService> passwordPolicyRuleService,
        UserSecurityEventPublisher securityEventPublisher,
        UserSessionRevocationService sessionRevocationService,
        UserSessionPresenceService sessionPresenceService
) {
    public UserAccountSecurityServices {
        passwordPolicyRuleService = passwordPolicyRuleService == null ? Optional.empty() : passwordPolicyRuleService;
        Objects.requireNonNull(securityEventPublisher, "securityEventPublisher");
        Objects.requireNonNull(sessionRevocationService, "sessionRevocationService");
        Objects.requireNonNull(sessionPresenceService, "sessionPresenceService");
    }
}
