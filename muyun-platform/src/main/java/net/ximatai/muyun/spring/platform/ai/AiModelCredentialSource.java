package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/** Where the model credential is obtained. */
public enum AiModelCredentialSource implements CodeTitleEnum {
    DIRECT("direct", "直接填写"),
    ENVIRONMENT("environment", "服务器环境变量");

    private final String code;
    private final String title;

    AiModelCredentialSource(String code, String title) {
        this.code = code;
        this.title = title;
    }

    @Override public String getCode() { return code; }
    @Override public String getTitle() { return title; }
}
