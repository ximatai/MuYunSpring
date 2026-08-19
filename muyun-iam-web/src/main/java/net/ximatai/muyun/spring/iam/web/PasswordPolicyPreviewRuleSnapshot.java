package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.iam.user.PasswordPolicyRule;

/**
 * Read-only rule projection used by the IAM-owned browser password preview.
 *
 * <p>The preview needs the same active global policy set as server-side password
 * validation, but it must not receive edit-only scope or lifecycle fields.</p>
 */
public record PasswordPolicyPreviewRuleSnapshot(
        String id,
        String title,
        String pattern,
        String message,
        boolean enabled,
        String scopeType,
        Integer sortOrder
) {
    static PasswordPolicyPreviewRuleSnapshot from(PasswordPolicyRule rule) {
        return new PasswordPolicyPreviewRuleSnapshot(
                rule.getId(),
                rule.getTitle(),
                rule.getPattern(),
                rule.getMessage(),
                Boolean.TRUE.equals(rule.getEnabled()),
                rule.getScopeType() == null ? null : rule.getScopeType().getCode(),
                rule.getSortOrder());
    }
}
