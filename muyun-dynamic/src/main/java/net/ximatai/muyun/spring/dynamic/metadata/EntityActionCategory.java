package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

public enum EntityActionCategory implements CodeTitleEnum {
    STANDARD("STANDARD", "标准动作"),
    CUSTOM("CUSTOM", "自定义动作"),
    DIALOG("DIALOG", "对话动作"),
    WORKFLOW("WORKFLOW", "工作流动作"),
    GENERATE("GENERATE", "生成动作");

    private final String code;
    private final String title;

    EntityActionCategory(String code, String title) {
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
