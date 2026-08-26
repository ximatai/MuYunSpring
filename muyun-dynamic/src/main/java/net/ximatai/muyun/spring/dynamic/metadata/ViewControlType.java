package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

public enum ViewControlType implements CodeTitleEnum {
    TEXT("文本框"),
    TEXTAREA("文本域"),
    NUMBER("数字"),
    SWITCH("开关"),
    DATE("日期"),
    DATETIME("日期时间"),
    DECIMAL("小数"),
    SELECT("下拉单选"),
    MULTI_SELECT("下拉多选"),
    COLOR_PICKER("颜色选择器"),
    JSON("JSON");

    private final String title;

    ViewControlType(String title) {
        this.title = title;
    }

    @Override
    public String getCode() {
        return name();
    }

    @Override
    public String getTitle() {
        return title;
    }
}
