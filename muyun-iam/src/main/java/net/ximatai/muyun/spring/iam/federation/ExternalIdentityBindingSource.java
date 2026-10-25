package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/** How an external identity became linked to a local account. */
public enum ExternalIdentityBindingSource implements CodeTitleEnum {
    SELF_BIND("selfBind", "本人绑定"),
    DIRECTORY_SYNC("directorySync", "通讯录同步");

    private final String code;
    private final String title;

    ExternalIdentityBindingSource(String code, String title) {
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
