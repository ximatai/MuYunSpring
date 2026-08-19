package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

public enum ModuleEntryType implements CodeTitleEnum {
    MODULE("module", "模块入口"),
    ROUTE("route", "内部路由"),
    LINK("link", "外部链接");

    private final String code;
    private final String title;

    ModuleEntryType(String code, String title) {
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
