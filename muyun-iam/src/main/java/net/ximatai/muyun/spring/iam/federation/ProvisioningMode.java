package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/** What happens when a verified external identity has no local account in the target tenant. */
public enum ProvisioningMode implements CodeTitleEnum {
    DISABLED("disabled", "不自动开户"),
    AUTO("auto", "自动开户"),
    REVIEW("review", "提交审核");

    private final String code;
    private final String title;

    ProvisioningMode(String code, String title) {
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
