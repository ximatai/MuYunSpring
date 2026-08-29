package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/** Target client of a page presentation variant. */
public enum PlatformPresentationClientType implements CodeTitleEnum {
    WEB("web", "Web"),
    MOBILE("mobile", "移动端");

    private final String code;
    private final String title;

    PlatformPresentationClientType(String code, String title) {
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
