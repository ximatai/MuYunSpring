package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/** Stable management overview patterns available to dynamic modules. */
public enum DynamicModuleOverviewMode implements CodeTitleEnum {
    TREE_CARD("tree_card", "树卡片"),
    LIST_CARD("list_card", "列表卡片"),
    MICRO_LIST_CARD("micro_list_card", "微列表卡片");

    private final String code;
    private final String title;

    DynamicModuleOverviewMode(String code, String title) {
        this.code = code;
        this.title = title;
    }

    @Override public String getCode() { return code; }
    @Override public String getTitle() { return title; }
}
