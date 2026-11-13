package net.ximatai.muyun.spring.iam.user;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomic policy/rule workspace command, shared by Web and other IAM callers. */
@Service
public class PasswordSecurityWorkspaceService {
    private final PasswordSecurityPolicyService policies;
    private final PasswordPolicyRuleService ruleService;

    public PasswordSecurityWorkspaceService(PasswordSecurityPolicyService policies, PasswordPolicyRuleService rules) {
        this.policies = Objects.requireNonNull(policies);
        this.ruleService = Objects.requireNonNull(rules);
    }

    /** The caller supplies its entry-boundary authorization and untrusted-field validation. */
    public interface Access {
        void authorize(String module, PlatformAction action, String recordId);
        void validate(EntityContract supplied, EntityContract existing);
    }

    public record RuleVersion(String id, Integer version) { }
    public record Command(String tenantId, PasswordSecurityPolicy policy,
                          List<RuleVersion> originalRules, List<PasswordPolicyRule> rules) { }

    @Transactional
    public PasswordSecurityPolicy save(Command request, Access access) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(access, "access");
        String target = request.tenantId() == null || request.tenantId().isBlank() ? null : request.tenantId().trim();
        String currentTenant = TenantContext.currentTenantId().orElse(null);
        if (!Objects.equals(currentTenant, target) && !TenantContext.isSystem())
            throw new PlatformAccessDeniedException("workspace target does not match the execution tenant");
        PasswordSecurityPolicy scope = new PasswordSecurityPolicy();
        scope.setTenantId(target);
        policies.requireMutationContext(scope);
        ruleService.lockWorkspace(target);
        List<PasswordPolicyRule> currentRules = ruleService.managedRulesForScope(target);
        Map<String, PasswordPolicyRule> current = new LinkedHashMap<>();
        currentRules.forEach(rule -> current.put(rule.getId(), rule));
        Map<String, Integer> baseline = new LinkedHashMap<>();
        boolean hasRuleDraft = request.originalRules() != null && request.rules() != null;
        if ((request.originalRules() == null) != (request.rules() == null)) {
            throw new IllegalArgumentException("complete rule snapshot and draft are required");
        }
        for (RuleVersion version : hasRuleDraft ? request.originalRules() : List.<RuleVersion>of()) {
            if (version == null || version.id() == null || version.version() == null
                    || baseline.putIfAbsent(version.id(), version.version()) != null) {
                throw new IllegalArgumentException("invalid rule snapshot");
            }
        }
        if (hasRuleDraft && (baseline.size() != current.size() || current.values().stream().anyMatch(rule ->
                !Objects.equals(rule.getVersion(), baseline.get(rule.getId()))))) {
            throw new OptimisticLockException("password rule set changed; reload and retry");
        }
        Map<String, PasswordPolicyRule> incoming = new LinkedHashMap<>();
        List<PasswordPolicyRule> added = new ArrayList<>();
        for (PasswordPolicyRule rule : hasRuleDraft ? request.rules() : List.<PasswordPolicyRule>of()) {
            if (rule == null) throw new IllegalArgumentException("rule must not be null");
            if (rule.getEnabled() == null) throw new IllegalArgumentException("rule enabled is required");
            if (rule.getTenantId() != null && !Objects.equals(target, rule.getTenantId())) {
                throw new PlatformAccessDeniedException("rule belongs to another tenant");
            }
            if (rule.getId() == null || rule.getId().isBlank()) {
                added.add(rule);
            } else if (!current.containsKey(rule.getId()) || incoming.putIfAbsent(rule.getId(), rule) != null
                    || !Objects.equals(rule.getVersion(), baseline.get(rule.getId()))) {
                throw new IllegalArgumentException("invalid or stale rule draft");
            }
        }
        PasswordSecurityPolicy existingPolicy = policies.findManagedByScope(target).orElse(null);
        PasswordSecurityPolicy policy = request.policy();
        if (policy == null && existingPolicy != null) {
            access.authorize(PasswordSecurityPolicyService.MODULE_ALIAS, PlatformAction.VIEW, existingPolicy.getId());
        }
        if (policy != null) {
            if (policy.getTenantId() != null && !Objects.equals(target, policy.getTenantId())) {
                throw new PlatformAccessDeniedException("policy belongs to another tenant");
            }
            if (existingPolicy == null) {
                if (policy.getId() != null && !policy.getId().isBlank()) throw new IllegalArgumentException("invalid new policy id");
                access.authorize(PasswordSecurityPolicyService.MODULE_ALIAS, PlatformAction.CREATE, null);
            } else {
                if (!Objects.equals(existingPolicy.getId(), policy.getId()) || policy.getVersion() == null
                        || !Objects.equals(existingPolicy.getVersion(), policy.getVersion())) {
                    throw new OptimisticLockException("password policy changed; reload and retry");
                }
                access.authorize(PasswordSecurityPolicyService.MODULE_ALIAS, PlatformAction.UPDATE, policy.getId());
            }
            access.validate(policy, existingPolicy);
            PasswordSecurityPolicy accepted = existingPolicy == null ? new PasswordSecurityPolicy() : existingPolicy;
            accepted.setTenantId(target);
            accepted.setComplexityMode(policy.getComplexityMode());
            accepted.setLockoutMode(policy.getLockoutMode());
            accepted.setMaxFailedAttempts(policy.getMaxFailedAttempts());
            accepted.setLockDurationSeconds(policy.getLockDurationSeconds());
            policy = accepted;
        }
        for (PasswordPolicyRule old : hasRuleDraft ? currentRules : List.<PasswordPolicyRule>of()) {
            PasswordPolicyRule draft = incoming.get(old.getId());
            if (draft == null) {
                access.authorize(PasswordPolicyRuleService.MODULE_ALIAS, PlatformAction.DELETE, old.getId());
                ruleService.delete(old.getId(), old.getVersion());
                continue;
            }
            boolean edited = !Objects.equals(old.getTitle(), draft.getTitle())
                    || !Objects.equals(old.getPattern(), draft.getPattern())
                    || !Objects.equals(old.getMessage(), draft.getMessage())
                    || !Objects.equals(old.getDescription(), draft.getDescription());
            boolean sorted = !Objects.equals(old.getSortOrder(), draft.getSortOrder());
            boolean enabled = !Objects.equals(old.getEnabled(), draft.getEnabled());
            if (!edited && !sorted && !enabled) continue;
            if (edited) access.authorize(PasswordPolicyRuleService.MODULE_ALIAS, PlatformAction.UPDATE, old.getId());
            if (sorted) access.authorize(PasswordPolicyRuleService.MODULE_ALIAS, PlatformAction.SORT, old.getId());
            if (enabled) access.authorize(PasswordPolicyRuleService.MODULE_ALIAS,
                    Boolean.TRUE.equals(draft.getEnabled()) ? PlatformAction.ENABLE : PlatformAction.DISABLE, old.getId());
            access.validate(draft, old);
            old.setTitle(draft.getTitle());
            old.setPattern(draft.getPattern());
            old.setMessage(draft.getMessage());
            old.setDescription(draft.getDescription());
            old.setSortOrder(draft.getSortOrder());
            old.setEnabled(draft.getEnabled());
            ruleService.update(old);
        }
        for (PasswordPolicyRule draft : added) {
            access.authorize(PasswordPolicyRuleService.MODULE_ALIAS, PlatformAction.CREATE, null);
            access.validate(draft, null);
            PasswordPolicyRule accepted = new PasswordPolicyRule();
            accepted.setTenantId(target);
            accepted.setTitle(draft.getTitle());
            accepted.setPattern(draft.getPattern());
            accepted.setMessage(draft.getMessage());
            accepted.setDescription(draft.getDescription());
            accepted.setSortOrder(draft.getSortOrder());
            accepted.setEnabled(draft.getEnabled());
            ruleService.insert(accepted);
        }
        if (policy != null) {
            if (existingPolicy == null) policies.insert(policy); else policies.update(policy);
        }
        return policies.findManagedByScope(target).orElseThrow(
                () -> new IllegalArgumentException("workspace save requires a policy record"));
    }
}
