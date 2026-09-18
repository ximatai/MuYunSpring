package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/** Ownership scope of the single effective configuration for a platform or tenant. */
public enum AiModelAvailabilityScope implements CodeTitleEnum {
    TENANT_PRIVATE("tenantPrivate", "租户级"),
    PLATFORM("platform", "全局级");

    private final String code;
    private final String title;

    AiModelAvailabilityScope(String code, String title) {
        this.code = code;
        this.title = title;
    }

    @Override
    public String getCode() { return code; }

    @Override
    public String getTitle() { return title; }
}
