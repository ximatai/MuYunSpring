package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/** Stable platform-recognized purpose of a system-managed role. */
public enum RoleSystemPurpose implements CodeTitleEnum {
    NONE("none", "无"),
    TENANT_ADMIN("tenantAdmin", "租户管理员"),
    ORGANIZATION_ADMIN("organizationAdmin", "机构管理员");

    private final String code;
    private final String title;

    RoleSystemPurpose(String code, String title) {
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
