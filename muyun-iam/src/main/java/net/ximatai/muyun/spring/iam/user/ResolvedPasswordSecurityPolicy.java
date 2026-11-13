package net.ximatai.muyun.spring.iam.user;

import java.time.Duration;
import java.util.List;

/**
 * Immutable, resolved effective password security policy for one target tenant.
 *
 * <p>Produced by {@link PasswordSecurityPolicyResolver}. It captures both independent groups (password
 * complexity and login failure lockout) together with where each value came from, so a caller can use
 * a single consistent snapshot for one authentication attempt.</p>
 */
public record ResolvedPasswordSecurityPolicy(
        String tenantId,
        ComplexitySource complexitySource,
        List<EffectivePasswordRule> complexityRules,
        int maxFailedAttempts,
        Duration lockDuration,
        LockoutSource lockoutSource
) {
    /** Where the effective password complexity rule set came from. */
    public enum ComplexitySource { TENANT, PLATFORM, DEFAULT }

    /** Where the effective login failure lockout parameters came from. */
    public enum LockoutSource { TENANT, PLATFORM_DB }

    public ResolvedPasswordSecurityPolicy {
        complexityRules = complexityRules == null ? List.of() : List.copyOf(complexityRules);
    }

    /** True when the target tenant replaces the platform complexity group with its own complete rule set. */
    public boolean complexityCustom() {
        return complexitySource == ComplexitySource.TENANT;
    }

    /** True when lockout is enabled for this snapshot (a positive failure threshold). */
    public boolean lockoutEnabled() {
        return maxFailedAttempts > 0;
    }

    /** Projects the resolved lockout parameters onto the shared {@link LoginLockoutPolicy} contract. */
    public LoginLockoutPolicy lockoutPolicy() {
        return LoginLockoutPolicy.of(maxFailedAttempts, lockDuration);
    }
}
