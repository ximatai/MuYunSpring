package net.ximatai.muyun.spring.platform.job;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/** Display vocabulary; codes remain the persisted job protocol. */
public enum JobTriggerType implements CodeTitleEnum {
    SCHEDULE("定时触发"),
    MANUAL("手动触发"),
    QUEUE("队列触发");

    private final String title;
    JobTriggerType(String title) { this.title = title; }
    @Override public String getCode() { return name(); }
    @Override public String getTitle() { return title; }
}
