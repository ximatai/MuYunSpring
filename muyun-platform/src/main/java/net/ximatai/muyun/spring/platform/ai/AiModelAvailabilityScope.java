package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/** Ownership scope of the single effective configuration for a platform or tenant. */
public enum AiModelAvailabilityScope implements CodeTitleEnum {
    TENANT_PRIVATE("tenantPrivate", "租户级"),
    /** Legacy persisted value retained only so the one-time ownership migration can read old rows. */
    @Deprecated(forRemoval = true)
    SELECTED_TENANTS("selectedTenants", "待迁移的指定租户配置"),
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
