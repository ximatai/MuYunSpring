package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/** Ownership level of an AI model configuration. */
public enum AiModelConfigurationLevel implements CodeTitleEnum {
    TENANT("tenant", "租户级"),
    PLATFORM("platform", "平台级");

    private final String code;
    private final String title;

    AiModelConfigurationLevel(String code, String title) {
        this.code = code;
        this.title = title;
    }

    @Override
    public String getCode() { return code; }

    @Override
    public String getTitle() { return title; }
}
