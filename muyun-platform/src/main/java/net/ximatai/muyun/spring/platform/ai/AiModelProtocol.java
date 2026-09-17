package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/** A configuration-level protocol identifier, not a vendor SDK type. */
public enum AiModelProtocol implements CodeTitleEnum {
    OPENAI_COMPATIBLE("openaiCompatible", "OpenAI 兼容");

    private final String code;
    private final String title;

    AiModelProtocol(String code, String title) {
        this.code = code;
        this.title = title;
    }

    @Override
    public String getCode() { return code; }

    @Override
    public String getTitle() { return title; }
}
