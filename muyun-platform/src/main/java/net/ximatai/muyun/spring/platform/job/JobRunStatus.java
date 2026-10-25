package net.ximatai.muyun.spring.platform.job;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/** Display vocabulary; codes remain the persisted job protocol. */
public enum JobRunStatus implements CodeTitleEnum {
    PENDING("等待执行"),
    RUNNING("执行中"),
    RETRY("等待重试"),
    SUCCEEDED("执行成功"),
    PARTIAL("部分完成"),
    FAILED("执行失败"),
    CANCELLED("已取消");

    private final String title;
    JobRunStatus(String title) { this.title = title; }
    @Override public String getCode() { return name(); }
    @Override public String getTitle() { return title; }
}
