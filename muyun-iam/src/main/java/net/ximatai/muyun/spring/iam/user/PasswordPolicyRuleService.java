package net.ximatai.muyun.spring.iam.user;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.MutationScopeAbility;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import java.util.Objects;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.initialdata.InitialDataAbility;
import net.ximatai.muyun.spring.ability.initialdata.InitialDataOptions;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.util.Preconditions;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Maintains password complexity regex rules. Each rule belongs to exactly one ownership scope: the
 * platform default (tenantId null, the legacy global rules) or a tenant (tenantId set).
 *
 * <p>The {@code tenantId} is the single source of truth for ownership; the persisted
 * {@code scopeType} / {@code scopeId} / {@code scopeKey} columns are normalized from it on the server
 * and cannot be forged to cross scopes.</p>
 */
@Service
public class PasswordPolicyRuleService extends AbstractAbilityService<PasswordPolicyRule> implements
        MutationScopeAbility<PasswordPolicyRule>,
        net.ximatai.muyun.spring.ability.child.ChildAbility<PasswordPolicyRule>,
        net.ximatai.muyun.spring.platform.localization.RecordTextSourceAbility<PasswordPolicyRule>,
        SoftDeleteAbility<PasswordPolicyRule>,
        EnableAbility<PasswordPolicyRule>,
        SortAbility<PasswordPolicyRule>,
        ReferenceAbility<PasswordPolicyRule>,
        InitialDataAbility<PasswordPolicyRule>,
        QueryAbility<PasswordPolicyRule> {
    public static final String MODULE_ALIAS = "iam.password_policy_rule";
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);
    private static final String GLOBAL_SCOPE_KEY = "global:";
    private static final String TENANT_SCOPE_KEY_PREFIX = "tenant:";
    private static final PasswordPolicyRule DEFAULT_MIN_LENGTH_RULE = defaultMinLengthRule();

    public PasswordPolicyRuleService(PasswordPolicyRuleDao passwordPolicyRuleDao) {
        super(MODULE_ALIAS, PasswordPolicyRule.class, passwordPolicyRuleDao);
    }

    /** All direct rule mutations and workspace saves serialize on the same ownership scope. */
    @Override
    public java.util.function.Function<PasswordPolicyRule, String> mutationParentKey() {
        return rule -> PasswordSecurityPolicyService.scopeKeyOf(rule.getTenantId());
    }

    public void lockWorkspace(String tenantId) {
        lockParentMutation(PasswordSecurityPolicyService.scopeKeyOf(tenantId));
    }

    @Override
    public void reorder(Criteria scope, List<String> orderedIds) {
        inMutationTransaction(() -> {
            if (orderedIds != null && !orderedIds.isEmpty()) lockSortScope(orderedIds.getFirst());
            SortAbility.super.reorder(scope, orderedIds);
            return null;
        });
    }

    @Override
    public void moveBefore(String id, String beforeId) {
        inMutationTransaction(() -> {
            lockSortScope(id);
            SortAbility.super.moveBefore(id, beforeId);
            return null;
        });
    }

    @Override
    public void moveAfter(String id, String afterId) {
        inMutationTransaction(() -> {
            lockSortScope(id);
            SortAbility.super.moveAfter(id, afterId);
            return null;
        });
    }

    private void lockSortScope(String id) {
        PasswordPolicyRule rule = select(id);
        if (rule != null) lockWorkspace(rule.getTenantId());
    }

    /** Complete active (including disabled) managed rules in one ownership scope. */
    public List<PasswordPolicyRule> managedRulesForScope(String tenantId) {
        Criteria criteria = Criteria.of();
        if (tenantId == null || tenantId.isBlank()) {
            criteria.isNull("tenantId").eq("scopeType", PasswordPolicyScopeType.GLOBAL);
        } else {
            criteria.eq("tenantId", tenantId).eq("scopeType", PasswordPolicyScopeType.TENANT);
        }
        return list(criteria, ALL, Sort.asc(PlatformAbilityFields.SORT_FIELD), Sort.asc("id"));
    }

    @Override
    public InitialDataOptions initialDataOptions() {
        return InitialDataOptions.system("iam.password-policy-rule", 45)
                .policy(net.ximatai.muyun.spring.ability.initialdata.InitialDataPolicy.CREATE_IF_MISSING);
    }

    @Override
    public List<PasswordPolicyRule> initialData() {
        PasswordPolicyRule rule = defaultMinLengthRule();
        rule.setId("iam.password_rule.min_length");
        return List.of(rule);
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        QueryDescriptor.Builder builder = QueryDescriptor.builder(MODULE_ALIAS);
        List.of("id", "scopeType", "scopeId", "title", "pattern", "message",
                "enabled", "sortOrder", "createdAt", "updatedAt")
                .forEach(field -> builder.field(QueryDescriptors.field(PasswordPolicyRule.class, field)));
        return builder.field(net.ximatai.muyun.spring.ability.query.QueryField.of("tenantId",
                        net.ximatai.muyun.spring.ability.query.QueryOperator.EQ,
                        net.ximatai.muyun.spring.ability.query.QueryOperator.IN,
                        net.ximatai.muyun.spring.ability.query.QueryOperator.NULL).withTitle("租户"))
                .externalCriteria("tenantId", net.ximatai.muyun.spring.ability.query.QueryValueType.STRING,
                        net.ximatai.muyun.spring.ability.query.ExternalQueryValueSource.PAGE_CONTEXT,
                        value -> Criteria.of().eq("tenantId", Preconditions.requireText(String.valueOf(value), "tenantId")))
                .defaultSort(Sort.asc(PlatformAbilityFields.SORT_FIELD))
                .defaultSort(Sort.asc("title")).build();
    }

    @Override
    public void requireMutationContext(PasswordPolicyRule rule) {
        String currentTenant = normalizeBlank(TenantContext.currentTenantId().orElse(null));
        if (currentTenant != null) {
            String requested = normalizeBlank(rule.getTenantId());
            if (requested != null && !requested.equals(currentTenant)) {
                throw new PlatformAccessDeniedException("cannot write another tenant's password policy rule");
            }
            rule.setTenantId(currentTenant);
            return;
        }
        if (TenantContext.isSystem()) {
            rule.setTenantId(normalizeBlank(rule.getTenantId()));
            return;
        }
        throw new PlatformAccessDeniedException(
                "password policy rule management requires an explicit system or tenant context");
    }

    @Override
    public boolean allowsTenantOwnershipChange(PasswordPolicyRule existing, PasswordPolicyRule incoming) {
        String existingTenant = normalizeBlank(existing.getTenantId());
        String incomingTenant = normalizeBlank(incoming.getTenantId());
        if (incomingTenant != null && !incomingTenant.equals(existingTenant)) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "iam.rejection.password-policy-rule.ownership-cannot-change",
                    "password policy rule ownership cannot be changed after creation"));
        }
        return false;
    }

    @Override
    public void beforeInsert(PasswordPolicyRule rule) {
        normalizeAndValidate(rule, null);
    }

    @Override
    public void beforeUpdate(PasswordPolicyRule rule, PasswordPolicyRule existing) {
        normalizeAndValidate(rule, existing);
    }

    /** Legacy entry point: validates against the platform default global rules (with min-length fallback). */
    public List<PasswordPolicyRule> activeGlobalRules() {
        List<PasswordPolicyRule> rules = platformActiveRules();
        return rules.isEmpty() ? List.of(DEFAULT_MIN_LENGTH_RULE) : rules;
    }

    /** Active platform (global) rules; may be empty when only the code fallback applies. */
    public List<PasswordPolicyRule> platformActiveRules() {
        return activeRulesForScope(null);
    }

    /** Active rules owned by the given tenant; empty when the tenant has none configured. */
    public List<PasswordPolicyRule> tenantActiveRules(String tenantId) {
        String normalized = normalizeBlank(tenantId);
        return normalized == null ? List.of() : activeRulesForScope(normalized);
    }

    private List<PasswordPolicyRule> activeRulesForScope(String tenantId) {
        try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter("read password policy rules")) {
            Criteria criteria = Criteria.of().eq("enabled", Boolean.TRUE);
            if (tenantId == null) {
                // Platform rules are the legacy global rules: tenantId null AND scopeType GLOBAL. A reserved
                // historical row with tenantId null but scopeType TENANT must never be absorbed as platform.
                criteria.isNull("tenantId");
                criteria.eq("scopeType", PasswordPolicyScopeType.GLOBAL);
            } else {
                criteria.eq("tenantId", tenantId);
                criteria.eq("scopeType", PasswordPolicyScopeType.TENANT);
            }
            return list(criteria, ALL,
                    Sort.asc(PlatformAbilityFields.SORT_FIELD),
                    Sort.asc("title"));
        }
    }

    /** Legacy global validation; tenant-aware callers go through {@link PasswordSecurityPolicyResolver}. */
    public void validatePassword(String password) {
        for (PasswordPolicyRule rule : activeGlobalRules()) {
            if (!Pattern.compile(rule.getPattern()).matcher(password == null ? "" : password).matches()) {
                throw BusinessExceptions.warningText("iam.user.password-policy-violated", PasswordPolicyTextSources.message(rule));
            }
        }
    }

    private void normalizeAndValidate(PasswordPolicyRule rule, PasswordPolicyRule existing) {
        // Ownership (tenant) is set by requireMutationContext before this hook; enforce immutability.
        String tenantId = normalizeBlank(rule.getTenantId());
        if (existing != null) {
            String existingTenant = normalizeBlank(existing.getTenantId());
            if (!Objects.equals(existingTenant, tenantId)) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "iam.rejection.password-policy-rule.ownership-cannot-change",
                        "password policy rule ownership cannot be changed after creation"));
            }
        }
        rule.setTenantId(tenantId);
        if (tenantId == null) {
            rule.setScopeType(PasswordPolicyScopeType.GLOBAL);
            rule.setScopeId(null);
            rule.setScopeKey(GLOBAL_SCOPE_KEY);
        } else {
            rule.setScopeType(PasswordPolicyScopeType.TENANT);
            rule.setScopeId(tenantId);
            rule.setScopeKey(TENANT_SCOPE_KEY_PREFIX + tenantId);
        }
        if (rule.getEnabled() == null) {
            rule.setEnabled(Boolean.TRUE);
        }
        if (rule.getPattern() != null) {
            validatePattern(rule.getPattern());
        }
    }

    private static void validatePattern(String pattern) {
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException ex) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "iam.rejection.password-policy-rule.invalid-password-policy-regex",
                    "invalid password policy regex: {value1}"), Map.of("value1", String.valueOf(ex.getDescription())));
        }
    }

    /** The established minimum-length fallback used when no active rule set is configured. */
    public static PasswordPolicyRule defaultMinLengthRule() {
        PasswordPolicyRule rule = new PasswordPolicyRule();
        rule.setScopeType(PasswordPolicyScopeType.GLOBAL);
        rule.setScopeKey(GLOBAL_SCOPE_KEY);
        rule.setTitle("密码长度");
        rule.setPattern("^.{6,}$");
        rule.setMessage("密码长度不能少于 6 位");
        rule.setEnabled(Boolean.TRUE);
        rule.setSortOrder(10);
        return rule;
    }

    private static String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    public java.util.Map<String, net.ximatai.muyun.spring.common.i18n.TextReference> recordTextSources(PasswordPolicyRule record) {
        var result = new java.util.LinkedHashMap<String, net.ximatai.muyun.spring.common.i18n.TextReference>();
        if (record.getTitle() != null && !record.getTitle().isBlank()) result.put("title", PasswordPolicyTextSources.title(record));
        if (record.getMessage() != null && !record.getMessage().isBlank()) result.put("message", PasswordPolicyTextSources.message(record));
        return java.util.Map.copyOf(result);
    }
}
