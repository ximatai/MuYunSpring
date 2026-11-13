package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.MutationScopeAbility;
import net.ximatai.muyun.spring.ability.initialdata.InitialDataAbility;
import net.ximatai.muyun.spring.ability.initialdata.InitialDataOptions;
import net.ximatai.muyun.spring.ability.initialdata.InitialDataPolicy;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.platform.RecordActionAvailabilityContributor;
import net.ximatai.muyun.spring.common.platform.RecordActionAvailabilityDecision;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Maintains the platform default and per-tenant {@link PasswordSecurityPolicy} records.
 *
 * <p>Ownership rules are enforced by {@link #requireMutationContext}, the standard mutation-scope
 * lifecycle hook that runs before tenant-filter rewrites and business hooks:</p>
 * <ul>
 *   <li>A tenant context forces the record to the caller's own tenant and rejects any attempt to write
 *       the platform default or another tenant.</li>
 *   <li>Only an explicit system scope (platform super administrator) may write the platform default or
 *       a specific tenant, and the target tenant must exist and be active.</li>
 *   <li>A missing execution scope is never treated as authorization; the mutation is denied.</li>
 *   <li>Ownership (tenant + deterministic id) is fixed at creation and cannot be migrated; the primary
 *       key and the unique ownership-scope index are the database concurrency backstops.</li>
 * </ul>
 */
@Service
public class PasswordSecurityPolicyService extends AbstractAbilityService<PasswordSecurityPolicy>
        implements MutationScopeAbility<PasswordSecurityPolicy>, QueryAbility<PasswordSecurityPolicy>,
        InitialDataAbility<PasswordSecurityPolicy>, RecordActionAvailabilityContributor {
    public static final String MODULE_ALIAS = "iam.password_security_policy";
    /** Fixed platform scope identity within the standard 32 char id limit. */
    public static final String PLATFORM_POLICY_ID = "iam.psp.platform";
    /** Prefix for the deterministic tenant scope identity (8 chars + 24 hash chars = 32). */
    public static final String TENANT_POLICY_ID_PREFIX = "iam.psp.";
    public static final String PLATFORM_SCOPE_KEY = "P";
    public static final String TENANT_SCOPE_PREFIX = "T:";
    public static final int MAX_FAILED_ATTEMPTS_LIMIT = 1000;
    public static final int MAX_LOCK_DURATION_SECONDS_LIMIT = 31_536_000;
    private static final int TENANT_ID_HASH_LENGTH = 24;

    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final TenantService tenantService;
    private final PasswordPolicyRuleService passwordPolicyRules;

    public PasswordSecurityPolicyService(PasswordSecurityPolicyDao dao, TenantService tenantService,
                                         PasswordPolicyRuleService passwordPolicyRules) {
        super(MODULE_ALIAS, PasswordSecurityPolicy.class, dao);
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
        this.passwordPolicyRules = Objects.requireNonNull(passwordPolicyRules, "passwordPolicyRules must not be null");
    }

    @Override
    public InitialDataOptions initialDataOptions() {
        return InitialDataOptions.system("iam.password-security-policy", 40)
                .policy(InitialDataPolicy.CREATE_IF_MISSING);
    }

    @Override
    public List<PasswordSecurityPolicy> initialData() {
        PasswordSecurityPolicy policy = new PasswordSecurityPolicy();
        policy.setId(PLATFORM_POLICY_ID);
        policy.setTitle("平台默认密码安全策略");
        policy.setOwnershipScopeKey(PLATFORM_SCOPE_KEY);
        policy.setComplexityMode(PasswordSecurityPolicyMode.CUSTOM);
        policy.setLockoutMode(PasswordSecurityPolicyMode.CUSTOM);
        policy.setMaxFailedAttempts(LoginLockoutPolicy.DEFAULT_MAX_FAILED_ATTEMPTS);
        policy.setLockDurationSeconds((int) LoginLockoutPolicy.DEFAULT_LOCK_DURATION.toSeconds());
        return List.of(policy);
    }

    @Override
    public void beforeDelete(String id) {
        if (PLATFORM_POLICY_ID.equals(id)) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "iam.rejection.password-security-policy.platform-default-cannot-delete",
                    "platform default password security policy cannot be deleted"));
        }
    }

    @Override
    public Optional<RecordActionAvailabilityDecision> availability(String moduleAlias, String actionCode, String recordId) {
        if (MODULE_ALIAS.equals(moduleAlias) && "delete".equals(actionCode) && PLATFORM_POLICY_ID.equals(recordId)) {
            return Optional.of(RecordActionAvailabilityDecision.unavailableText(TextReference.code(
                    "iam.rejection.password-security-policy.platform-default-cannot-delete",
                    "platform default password security policy cannot be deleted")));
        }
        return Optional.empty();
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        QueryDescriptor.Builder builder = QueryDescriptor.builder(MODULE_ALIAS);
        List.of("id", "complexityMode", "lockoutMode", "maxFailedAttempts",
                "lockDurationSeconds", "createdAt", "updatedAt")
                .forEach(field -> builder.field(QueryDescriptors.field(PasswordSecurityPolicy.class, field)));
        return builder.field(net.ximatai.muyun.spring.ability.query.QueryField.of("tenantId",
                        net.ximatai.muyun.spring.ability.query.QueryOperator.EQ,
                        net.ximatai.muyun.spring.ability.query.QueryOperator.IN,
                        net.ximatai.muyun.spring.ability.query.QueryOperator.NULL).withTitle("租户"))
                .externalCriteria("tenantId", net.ximatai.muyun.spring.ability.query.QueryValueType.STRING,
                        net.ximatai.muyun.spring.ability.query.ExternalQueryValueSource.PAGE_CONTEXT,
                        value -> Criteria.of().eq("tenantId", net.ximatai.muyun.spring.common.util.Preconditions
                                .requireText(String.valueOf(value), "tenantId")))
                .build();
    }

    /** Deterministic fixed scope identity for a tenant; stable across instances and used as the PK. */
    public static String tenantPolicyId(String tenantId) {
        String normalized = tenantId == null ? "" : tenantId.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((TENANT_SCOPE_PREFIX + normalized).getBytes(StandardCharsets.UTF_8));
            return TENANT_POLICY_ID_PREFIX
                    + HexFormat.of().formatHex(digest).substring(0, TENANT_ID_HASH_LENGTH);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static String scopeKeyOf(String tenantId) {
        String normalized = normalizeBlankStatic(tenantId);
        return normalized == null ? PLATFORM_SCOPE_KEY : TENANT_SCOPE_PREFIX + normalized;
    }

    @Override
    public void requireMutationContext(PasswordSecurityPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        String currentTenant = normalizeBlank(TenantContext.currentTenantId().orElse(null));
        if (currentTenant != null) {
            String requested = normalizeBlank(policy.getTenantId());
            if (requested != null && !requested.equals(currentTenant)) {
                throw new PlatformAccessDeniedException("cannot write another tenant's password security policy");
            }
            policy.setTenantId(currentTenant);
            tenantService.requireActiveTenant(currentTenant);
            return;
        }
        if (TenantContext.isSystem()) {
            String requested = normalizeBlank(policy.getTenantId());
            if (requested != null) {
                // A specific tenant target must exist and be active; do not trust the browser picker.
                tenantService.requireActiveTenant(requested);
            }
            policy.setTenantId(requested);
            return;
        }
        throw new PlatformAccessDeniedException(
                "password security policy management requires an explicit system or tenant context");
    }

    @Override
    public boolean allowsTenantOwnershipChange(PasswordSecurityPolicy existing, PasswordSecurityPolicy incoming) {
        String existingTenant = normalizeBlank(existing.getTenantId());
        String incomingTenant = normalizeBlank(incoming.getTenantId());
        if (incomingTenant != null && !incomingTenant.equals(existingTenant)) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "iam.rejection.password-security-policy.ownership-cannot-change",
                    "password security policy ownership cannot be changed after creation"));
        }
        return false;
    }

    @Override
    public void normalizeBeforeMutation(PasswordSecurityPolicy policy) {
        String tenantId = normalizeBlank(policy.getTenantId());
        policy.setTenantId(tenantId);
        String expectedId = tenantId == null ? PLATFORM_POLICY_ID : tenantPolicyId(tenantId);
        String incomingId = normalizeBlank(policy.getId());
        if (incomingId != null && !incomingId.equals(expectedId)) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "iam.rejection.password-security-policy.invalid-policy-id",
                    "password security policy identity is derived from its ownership scope"));
        }
        policy.setId(expectedId);
        if (tenantId == null) {
            policy.setComplexityMode(PasswordSecurityPolicyMode.CUSTOM);
            policy.setLockoutMode(PasswordSecurityPolicyMode.CUSTOM);
            policy.setTitle("平台默认密码安全策略");
        } else {
            if (policy.getComplexityMode() == null) {
                policy.setComplexityMode(PasswordSecurityPolicyMode.INHERIT);
            }
            if (policy.getLockoutMode() == null) {
                policy.setLockoutMode(PasswordSecurityPolicyMode.INHERIT);
            }
            policy.setTitle("租户密码安全策略");
        }
        policy.setOwnershipScopeKey(scopeKeyOf(tenantId));
        validateLockout(policy);
    }

    @Override
    public void beforeInsert(PasswordSecurityPolicy policy) {
        requireNoExistingScope(policy.getId());
        requireRulesWhenActivatingCustom(policy, null);
    }

    @Override
    public void beforeUpdate(PasswordSecurityPolicy policy, PasswordSecurityPolicy existing) {
        if (existing == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "iam.rejection.password-security-policy.policy-does-not-exist",
                    "password security policy does not exist"));
        }
        requireRulesWhenActivatingCustom(policy, existing);
        // Updating the same scope reuses its fixed primary key; uniqueness is only meaningful on insert.
    }

    private void requireRulesWhenActivatingCustom(PasswordSecurityPolicy policy, PasswordSecurityPolicy existing) {
        if (policy.getTenantId() == null || policy.getComplexityMode() != PasswordSecurityPolicyMode.CUSTOM
                || (existing != null && existing.getComplexityMode() == PasswordSecurityPolicyMode.CUSTOM)) return;
        if (passwordPolicyRules.tenantActiveRules(policy.getTenantId()).isEmpty()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "iam.rejection.password-security-policy.custom-rules-required",
                    "at least one enabled tenant password rule is required before enabling custom complexity"));
        }
    }

    /**
     * Trusted internal read for the effective-policy resolver: reads a scope regardless of the ambient
     * tenant context. Management reads go through normal CRUD scope and {@link #findManagedByScope}.
     *
     * @hidden Internal IAM collaborator of the password-policy resolver; not an authorization entry point.
     */
    public Optional<PasswordSecurityPolicy> findForResolution(String tenantId) {
        String normalized = normalizeBlank(tenantId);
        try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter("resolve password security policy")) {
            Criteria criteria = Criteria.of();
            if (normalized == null) {
                criteria.isNull("tenantId");
            } else {
                criteria.eq("tenantId", normalized);
            }
            return list(criteria, ALL).stream().findFirst();
        }
    }

    /** @hidden Internal collaborator used by the resolver; platform default (tenantId null). */
    public Optional<PasswordSecurityPolicy> findPlatformPolicy() {
        return findForResolution(null);
    }

    /** @hidden Internal collaborator used by the resolver; a specific tenant scope. */
    public Optional<PasswordSecurityPolicy> findTenantPolicy(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? Optional.empty() : findForResolution(tenantId);
    }

    /**
     * Management read that respects the caller's tenant scope: a tenant caller sees only its own record,
     * the platform default is only visible from a system scope. Never bypasses the tenant filter.
     */
    public Optional<PasswordSecurityPolicy> findManagedByScope(String tenantId) {
        String normalized = normalizeBlank(tenantId);
        Criteria criteria = Criteria.of();
        if (normalized == null) {
            criteria.isNull("tenantId");
        } else {
            criteria.eq("tenantId", normalized);
        }
        return list(criteria, ALL).stream().findFirst();
    }

    private void validateLockout(PasswordSecurityPolicy policy) {
        boolean tenantInherit = policy.getTenantId() != null
                && policy.getLockoutMode() != PasswordSecurityPolicyMode.CUSTOM;
        if (tenantInherit) {
            policy.setMaxFailedAttempts(null);
            policy.setLockDurationSeconds(null);
            return;
        }
        Integer max = policy.getMaxFailedAttempts();
        Integer duration = policy.getLockDurationSeconds();
        if (max == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "iam.rejection.password-security-policy.max-failed-attempts-required",
                    "custom lockout requires a max failed attempts threshold"));
        }
        if (max < 0) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "iam.rejection.password-security-policy.max-failed-attempts-negative",
                    "max failed attempts must not be negative"));
        }
        if (max > MAX_FAILED_ATTEMPTS_LIMIT) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "iam.rejection.password-security-policy.max-failed-attempts-too-large",
                    "max failed attempts must not exceed {value1}"), Map.of("value1", MAX_FAILED_ATTEMPTS_LIMIT));
        }
        if (max == 0) {
            policy.setLockDurationSeconds(null);
            return;
        }
        if (duration == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "iam.rejection.password-security-policy.lock-duration-required",
                    "lockout requires a positive lock duration in seconds"));
        }
        if (duration <= 0) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "iam.rejection.password-security-policy.lock-duration-not-positive",
                    "lock duration must be a positive number of seconds"));
        }
        if (duration > MAX_LOCK_DURATION_SECONDS_LIMIT) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "iam.rejection.password-security-policy.lock-duration-too-large",
                    "lock duration must not exceed {value1} seconds"), Map.of("value1", MAX_LOCK_DURATION_SECONDS_LIMIT));
        }
    }

    private void requireNoExistingScope(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        Criteria criteria = Criteria.of().eq("id", id);
        if (count(criteria) > 0) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "iam.rejection.password-security-policy.scope-already-configured",
                    "the password security policy scope already has a configuration"));
        }
    }

    private static String normalizeBlank(String value) {
        return normalizeBlankStatic(value);
    }

    private static String normalizeBlankStatic(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
