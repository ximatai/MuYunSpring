package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/** Explicit generation preference; null configuration keeps the provider's default. */
public enum AiModelReasoningEffort implements CodeTitleEnum {
    NONE("none", "关闭思考"),
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高"),
    MAX("max", "最高");

    private final String code;
    private final String title;

    AiModelReasoningEffort(String code, String title) {
        this.code = code;
        this.title = title;
    }

    @Override public String getCode() { return code; }
    @Override public String getTitle() { return title; }
}
