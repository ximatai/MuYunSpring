package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/**
 * Per-group inheritance choice for a tenant override of the platform password security policy.
 *
 * <p>{@link #INHERIT} uses the platform value for the whole group; {@link #CUSTOM} makes the tenant
 * own the complete effective value of that group. The two groups (password complexity and login
 * failure lockout) are independent and are never merged field by field.</p>
 */
public enum PasswordSecurityPolicyMode implements CodeTitleEnum {
    INHERIT("inherit", "继承平台"),
    CUSTOM("custom", "自定义");

    private final String code;
    private final String title;

    PasswordSecurityPolicyMode(String code, String title) {
        this.code = code;
        this.title = title;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getTitle() {
        return title;
    }
}
