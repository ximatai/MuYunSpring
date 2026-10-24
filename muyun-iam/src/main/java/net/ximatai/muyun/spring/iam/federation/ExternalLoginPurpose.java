package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

public enum ExternalLoginPurpose implements CodeTitleEnum {
    LOGIN("login", "登录"),
    BIND("bind", "绑定");

    private final String code;
    private final String title;

    ExternalLoginPurpose(String code, String title) {
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
