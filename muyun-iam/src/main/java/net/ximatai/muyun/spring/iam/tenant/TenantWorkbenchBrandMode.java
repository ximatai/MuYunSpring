package net.ximatai.muyun.spring.iam.tenant;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/** The single, tenant-owned rule for composing the workbench brand identity. */
public enum TenantWorkbenchBrandMode implements CodeTitleEnum {
    LOGO_ONLY("logoOnly", "纯 Logo"),
    LOGO_WITH_TITLE("logoWithTitle", "Logo + 标题");

    private final String code;
    private final String title;

    TenantWorkbenchBrandMode(String code, String title) {
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
