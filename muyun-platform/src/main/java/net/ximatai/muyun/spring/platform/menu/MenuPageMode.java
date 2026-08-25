package net.ximatai.muyun.spring.platform.menu;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

public enum MenuPageMode implements CodeTitleEnum {
    LIST("LIST", "列表"),
    FORM("FORM", "表单"),
    DETAIL("DETAIL", "详情");

    private final String code;
    private final String title;

    MenuPageMode(String code, String title) {
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
