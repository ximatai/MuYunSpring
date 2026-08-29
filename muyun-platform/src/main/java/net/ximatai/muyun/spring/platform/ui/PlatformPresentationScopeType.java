package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/** Scope of one page presentation variant. */
public enum PlatformPresentationScopeType implements CodeTitleEnum {
    GLOBAL("global", "全局"),
    TENANT("tenant", "租户"),
    ORGANIZATION("organization", "机构");

    private final String code;
    private final String title;

    PlatformPresentationScopeType(String code, String title) {
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
