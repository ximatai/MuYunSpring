package net.ximatai.muyun.spring.platform.job;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/** Display vocabulary; codes remain the persisted job protocol. */
public enum JobIdentityMode implements CodeTitleEnum {
    SYSTEM("系统身份"),
    INITIATOR("发起人身份"),
    SERVICE_ACCOUNT("服务账号（预留）");

    private final String title;
    JobIdentityMode(String title) { this.title = title; }
    @Override public String getCode() { return name(); }
    @Override public String getTitle() { return title; }
}
