package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/** Providers admitted by the first OpenAI-compatible model-connection contract. */
public enum AiModelProvider implements CodeTitleEnum {
    DEEPSEEK("deepseek", "DeepSeek", "https://api.deepseek.com"),
    BAILIAN("bailian", "阿里云百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
    LM_STUDIO("lmStudio", "LM Studio", "http://127.0.0.1:1234/v1");

    private final String code;
    private final String title;
    private final String baseUrl;

    AiModelProvider(String code, String title, String baseUrl) {
        this.code = code;
        this.title = title;
        this.baseUrl = baseUrl;
    }

    @Override
    public String getCode() { return code; }

    @Override
    public String getTitle() { return title; }

    public String baseUrl() { return baseUrl; }
}
