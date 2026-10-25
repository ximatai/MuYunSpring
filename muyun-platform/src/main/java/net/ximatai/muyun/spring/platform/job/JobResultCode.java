package net.ximatai.muyun.spring.platform.job;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/** Display vocabulary; codes remain the persisted job protocol. */
public enum JobResultCode implements CodeTitleEnum {
    COMPLETE("处理已完成"),
    BATCH_LIMIT_REACHED("本轮已达到批次上限，剩余数据待后续处理"),
    SKIPPED_ALREADY_RUNNING("已有同类任务运行，本轮已跳过"),
    CANCEL_REQUESTED("已收到取消请求"),
    ACTION_EXECUTED("目标动作已执行"),
    IDENTITY_WITHDRAWN("发起人身份已失效，请检查账号、任职与动作授权"),
    EXECUTION_FAILED("执行发生异常，请结合运行审计检查目标动作"),
    IDENTITY_SCOPE_MISMATCH("发起人与执行租户不一致"),
    SYSTEM_ACTION_NOT_WHITELISTED("目标动作不在系统身份白名单内"),
    IDENTITY_MODE_UNSUPPORTED("该作业不支持此执行身份"),
    CANCELLED("运行已取消"),
    ATTEMPTS_EXHAUSTED("已达到最大尝试次数"),
    MAXIMUM_RUNTIME_EXCEEDED("已超过最长运行时间"),
    LEASE_LOST("执行租约已失效");

    private final String title;
    JobResultCode(String title) { this.title = title; }
    @Override public String getCode() { return name(); }
    @Override public String getTitle() { return title; }
}
