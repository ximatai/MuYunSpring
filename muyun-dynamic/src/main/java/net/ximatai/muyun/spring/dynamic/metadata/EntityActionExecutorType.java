package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

public enum EntityActionExecutorType implements CodeTitleEnum {
    STANDARD("STANDARD", "标准执行器"),
    SERVICE("SERVICE", "服务执行器"),
    DIALOG("DIALOG", "对话执行器"),
    WORKFLOW("WORKFLOW", "工作流执行器"),
    GENERATE("GENERATE", "生成执行器");

    private final String code;
    private final String title;

    EntityActionExecutorType(String code, String title) {
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
