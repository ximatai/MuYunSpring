package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

public enum PasswordStatus implements CodeTitleEnum {
    NORMAL("normal", "正常"),
    INITIAL("initial", "初始密码"),
    RESET_REQUIRED("resetRequired", "需要重置"),
    EXPIRED("expired", "已过期"),
    /** The account has no local password and can only sign in through another authentication method. */
    NONE("none", "未设置");

    private final String code;
    private final String title;

    PasswordStatus(String code, String title) {
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
