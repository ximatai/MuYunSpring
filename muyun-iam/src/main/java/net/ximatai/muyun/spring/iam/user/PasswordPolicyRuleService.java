package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
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
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.util.Preconditions;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class PasswordPolicyRuleService extends AbstractAbilityService<PasswordPolicyRule> implements
        SoftDeleteAbility<PasswordPolicyRule>,
        EnableAbility<PasswordPolicyRule>,
        SortAbility<PasswordPolicyRule>,
        ReferenceAbility<PasswordPolicyRule>,
        InitialDataAbility<PasswordPolicyRule>,
        QueryAbility<PasswordPolicyRule> {
    public static final String MODULE_ALIAS = "iam.password_policy_rule";
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);
    private static final String GLOBAL_SCOPE_KEY = "global:";
    private static final PasswordPolicyRule DEFAULT_MIN_LENGTH_RULE = defaultMinLengthRule();

    public PasswordPolicyRuleService(PasswordPolicyRuleDao passwordPolicyRuleDao) {
        super(MODULE_ALIAS, PasswordPolicyRule.class, passwordPolicyRuleDao);
    }

    @Override
    public InitialDataOptions initialDataOptions() {
        return InitialDataOptions.system("iam.password-policy-rule", 70);
    }

    @Override
    public List<PasswordPolicyRule> initialData() {
        PasswordPolicyRule rule = defaultMinLengthRule();
        rule.setId("iam.password_rule.min_length");
        return List.of(rule);
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, PasswordPolicyRule.class,
                List.of("id", "scopeType", "scopeId", "title", "pattern", "message",
                        "enabled", "sortOrder", "createdAt", "updatedAt"),
                Sort.asc(PlatformAbilityFields.SORT_FIELD),
                Sort.asc("title"));
    }

    @Override
    public void beforeInsert(PasswordPolicyRule rule) {
        normalizeAndValidate(rule);
    }

    @Override
    public void beforeUpdate(PasswordPolicyRule rule) {
        normalizeAndValidate(rule);
    }


    public List<PasswordPolicyRule> activeGlobalRules() {
        List<PasswordPolicyRule> rules = configuredActiveGlobalRules();
        return rules.isEmpty() ? List.of(DEFAULT_MIN_LENGTH_RULE) : rules;
    }

    private List<PasswordPolicyRule> configuredActiveGlobalRules() {
        return list(Criteria.of()
                        .eq("scopeType", PasswordPolicyScopeType.GLOBAL)
                        .eq("scopeKey", GLOBAL_SCOPE_KEY)
                        .eq("enabled", Boolean.TRUE),
                ALL,
                Sort.asc(PlatformAbilityFields.SORT_FIELD),
                Sort.asc("title"));
    }

    public void validatePassword(String password) {
        for (PasswordPolicyRule rule : activeGlobalRules()) {
            if (!Pattern.compile(rule.getPattern()).matcher(password == null ? "" : password).matches()) {
                throw BusinessExceptions.warning("iam.user.password-policy-violated", rule.getMessage());
            }
        }
    }

    private void normalizeAndValidate(PasswordPolicyRule rule) {
        PasswordPolicyScopeType scopeType = rule.getScopeType() == null
                ? PasswordPolicyScopeType.GLOBAL
                : rule.getScopeType();
        rule.setScopeType(scopeType);
        rule.setScopeId(scopeType == PasswordPolicyScopeType.GLOBAL
                ? null
                : Preconditions.requireText(rule.getScopeId(), "scopeId").trim());
        rule.setScopeKey(scopeKey(rule));
        rule.setTitle(Preconditions.requireText(rule.getTitle(), "title").trim());
        rule.setPattern(Preconditions.requireText(rule.getPattern(), "pattern"));
        rule.setMessage(Preconditions.requireText(rule.getMessage(), "message").trim());
        if (rule.getEnabled() == null) {
            rule.setEnabled(Boolean.TRUE);
        }
        validatePattern(rule.getPattern());
    }

    private static String scopeKey(PasswordPolicyRule rule) {
        PasswordPolicyScopeType scopeType = rule.getScopeType() == null
                ? PasswordPolicyScopeType.GLOBAL
                : rule.getScopeType();
        String scopeId = rule.getScopeId() == null ? "" : rule.getScopeId().trim();
        return scopeType.getCode() + ":" + scopeId;
    }

    private static void validatePattern(String pattern) {
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException ex) {
            throw new PlatformException("invalid password policy regex: " + ex.getDescription());
        }
    }

    private static PasswordPolicyRule defaultMinLengthRule() {
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
}
