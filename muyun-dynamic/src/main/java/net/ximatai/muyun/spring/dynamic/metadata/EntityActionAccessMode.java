package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

public enum EntityActionAccessMode implements CodeTitleEnum {
    AUTH_REQUIRED("AUTH_REQUIRED", "需要授权"),
    LOGIN_REQUIRED("LOGIN_REQUIRED", "登录可用"),
    ANONYMOUS_ALLOWED("ANONYMOUS_ALLOWED", "匿名可用");

    private final String code;
    private final String title;

    EntityActionAccessMode(String code, String title) {
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
