package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/** Stable reasons an external sign-in or bind was denied; clients map codes to messages. */
public enum ExternalLoginFailure implements CodeTitleEnum {
    IDENTITY_NOT_BOUND("identityNotBound", "外部账号未绑定"),
    IDENTITY_CONFLICT("identityConflict", "外部账号已绑定其他账号"),
    ALREADY_BOUND("alreadyBound", "当前账号已绑定该身份源"),
    ENTERPRISE_MISMATCH("enterpriseMismatch", "外部企业不匹配"),
    POLICY_DISABLED("policyDisabled", "登录方式已停用"),
    ACCOUNT_UNAVAILABLE("accountUnavailable", "账号不可用"),
    /** The session that started a bind ended before the provider returned, so the bind is not completed. */
    SESSION_ENDED("sessionEnded", "发起绑定的登录已失效"),
    PROVIDER_REJECTED("providerRejected", "身份源拒绝授权"),
    PROVIDER_UNAVAILABLE("providerUnavailable", "身份源不可用"),
    /** A platform fault while completing the transaction, as opposed to a provider failure. */
    INTERNAL_ERROR("internalError", "平台处理失败");

    private final String code;
    private final String title;

    ExternalLoginFailure(String code, String title) {
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
