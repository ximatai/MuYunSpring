package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/**
 * Stable business contract of a page definition. Template composition belongs to a presentation variant.
 */
public enum PlatformPageContractType implements CodeTitleEnum {
    MANAGEMENT("management", "管理页"),
    FORM("form", "表单页"),
    DETAIL("detail", "详情页"),
    REFERENCE("reference", "引用页");

    private final String code;
    private final String title;

    PlatformPageContractType(String code, String title) {
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
