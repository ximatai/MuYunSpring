package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/** Describes which tenants may inherit a platform-owned model configuration. */
public enum AiModelAvailabilityScope implements CodeTitleEnum {
    TENANT_PRIVATE("tenantPrivate", "仅当前租户（优先）"),
    SELECTED_TENANTS("selectedTenants", "指定租户"),
    PLATFORM("platform", "所有租户");

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
