package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.spring.common.i18n.TextReference;

/** Policy messages are configuration text; the no-configuration fallback belongs to code. */
public final class PasswordPolicyTextSources {
    private PasswordPolicyTextSources() { }

    public static TextReference title(PasswordPolicyRule rule) {
        return source(rule, "title", rule.getTitle());
    }

    public static TextReference message(PasswordPolicyRule rule) {
        return source(rule, "message", rule.getMessage());
    }

    private static TextReference source(PasswordPolicyRule rule, String property, String original) {
        if (rule.getId() == null || rule.getId().isBlank())
            return TextReference.code("iam.password-policy.fallback." + property, original);
        return TextReference.configured("config/password-policy/" + rule.getId().replace("~", "~0").replace("/", "~1") + "/" + property, original);
    }
}
