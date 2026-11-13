package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Single entry point that resolves the effective password security policy for a target tenant.
 *
 * <p>Resolution order per group: the tenant override, then the initialized database platform default.
 * The two groups resolve
 * independently. The result is an immutable snapshot including the source of each value, so an
 * authentication attempt can make a consistent decision even if configuration changes concurrently.</p>
 *
 * <p>A tenant with no record simply inherits the platform/default policy; no per-tenant record is
 * pre-created. The database is read on demand rather than from a process-local cache, so a saved
 * change takes effect for subsequent requests on every node without a restart.</p>
 *
 * <p>The policy and rule services are required collaborators; the resolver is a mandatory security
 * dependency of the user-account service, never an optional one.</p>
 */
@Service
public class PasswordSecurityPolicyResolver {
    private final PasswordSecurityPolicyService policies;
    private final PasswordPolicyRuleService rules;

    public PasswordSecurityPolicyResolver(PasswordSecurityPolicyService policies,
                                          PasswordPolicyRuleService rules) {
        this.policies = Objects.requireNonNull(policies, "policies must not be null");
        this.rules = Objects.requireNonNull(rules, "rules must not be null");
    }

    /**
     * Resolves the effective policy for {@code tenantId} (null means platform/system accounts).
     *
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ,
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public ResolvedPasswordSecurityPolicy resolve(String tenantId) {
        String target = tenantId == null || tenantId.isBlank() ? null : tenantId.trim();
        PasswordSecurityPolicy tenantPolicy = target == null
                ? null : policies.findTenantPolicy(target).orElse(null);
        PasswordSecurityPolicy platformPolicy = policies.findPlatformPolicy().orElseThrow(
                () -> new IllegalStateException("platform default password security policy is not initialized"));

        ResolvedPasswordSecurityPolicy.ComplexitySource complexitySource;
        List<PasswordPolicyRule> effectiveRules;
        boolean tenantCustomComplexity = tenantPolicy != null
                && tenantPolicy.getComplexityMode() == PasswordSecurityPolicyMode.CUSTOM;
        if (tenantCustomComplexity) {
            List<PasswordPolicyRule> tenantRules = rules.tenantActiveRules(target);
            if (tenantRules.isEmpty()) {
                // An empty custom rule set must never silently become unconstrained; keep the
                // established minimum-length fallback and let the page state this explicitly.
                effectiveRules = List.of(PasswordPolicyRuleService.defaultMinLengthRule());
                complexitySource = ResolvedPasswordSecurityPolicy.ComplexitySource.DEFAULT;
            } else {
                effectiveRules = tenantRules;
                complexitySource = ResolvedPasswordSecurityPolicy.ComplexitySource.TENANT;
            }
        } else {
            List<PasswordPolicyRule> platformRules = rules.platformActiveRules();
            if (platformRules.isEmpty()) {
                effectiveRules = List.of(PasswordPolicyRuleService.defaultMinLengthRule());
                complexitySource = ResolvedPasswordSecurityPolicy.ComplexitySource.DEFAULT;
            } else {
                effectiveRules = platformRules;
                complexitySource = ResolvedPasswordSecurityPolicy.ComplexitySource.PLATFORM;
            }
        }
        List<EffectivePasswordRule> complexityRules = effectiveRules.stream()
                .map(rule -> new EffectivePasswordRule(rule.getId(), rule.getTenantId(), rule.getPattern(), PasswordPolicyTextSources.message(rule)))
                .toList();

        int maxFailedAttempts;
        Duration lockDuration;
        ResolvedPasswordSecurityPolicy.LockoutSource lockoutSource;
        if (tenantPolicy != null && tenantPolicy.getLockoutMode() == PasswordSecurityPolicyMode.CUSTOM
                && tenantPolicy.getMaxFailedAttempts() != null) {
            maxFailedAttempts = tenantPolicy.getMaxFailedAttempts();
            lockDuration = toDuration(tenantPolicy.getLockDurationSeconds());
            lockoutSource = ResolvedPasswordSecurityPolicy.LockoutSource.TENANT;
        } else {
            if (platformPolicy.getMaxFailedAttempts() == null) {
                throw new IllegalStateException("platform default lockout threshold is not configured");
            }
            maxFailedAttempts = platformPolicy.getMaxFailedAttempts();
            lockDuration = toDuration(platformPolicy.getLockDurationSeconds());
            lockoutSource = ResolvedPasswordSecurityPolicy.LockoutSource.PLATFORM_DB;
        }
        return new ResolvedPasswordSecurityPolicy(target, complexitySource, complexityRules,
                maxFailedAttempts, lockDuration, lockoutSource);
    }

    /**
     * Validates a candidate password against the effective complexity rules for the target tenant.
     * Used by every password-setting path so the target account's tenant decides the rules.
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ,
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void validateComplexity(String password, String tenantId) {
        validateComplexity(password, resolve(tenantId).complexityRules());
    }

    /** Validates against an already-resolved rule set, so a caller can reuse one snapshot. */
    public void validateComplexity(String password, List<EffectivePasswordRule> complexityRules) {
        for (EffectivePasswordRule rule : complexityRules) {
            if (!Pattern.compile(rule.pattern()).matcher(password == null ? "" : password).matches()) {
                throw BusinessExceptions.warningText("iam.user.password-policy-violated", rule.message());
            }
        }
    }

    /** Reusable predicate over a resolved rule set, avoiding repeated policy resolution. */
    public boolean matchesAll(String password, List<EffectivePasswordRule> complexityRules) {
        for (EffectivePasswordRule rule : complexityRules) {
            if (!Pattern.compile(rule.pattern()).matcher(password == null ? "" : password).matches()) {
                return false;
            }
        }
        return true;
    }

    private Duration toDuration(Integer seconds) {
        return seconds == null ? null : Duration.ofSeconds(seconds);
    }
}
