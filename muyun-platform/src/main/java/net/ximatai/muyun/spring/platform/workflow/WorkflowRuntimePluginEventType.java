package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

public enum WorkflowRuntimePluginEventType implements CodeTitleEnum {
    BEFORE_SUBMIT("before_submit", "提交前"),
    AFTER_SUBMIT("after_submit", "提交后"),
    BEFORE_APPROVE("before_approve", "同意前"),
    AFTER_APPROVE("after_approve", "同意后"),
    BEFORE_COMPLETE("before_complete", "业务任务完成前"),
    AFTER_COMPLETE("after_complete", "业务任务完成后"),
    BEFORE_TRANSFER("before_transfer", "转办前"),
    AFTER_TRANSFER("after_transfer", "转办后"),
    BEFORE_REJECT("before_reject", "驳回前"),
    AFTER_REJECT("after_reject", "驳回后"),
    BEFORE_ROLLBACK("before_rollback", "回退前"),
    AFTER_ROLLBACK("after_rollback", "回退后"),
    BEFORE_REVOKE("before_revoke", "撤回前"),
    AFTER_REVOKE("after_revoke", "撤回后"),
    BEFORE_RESET("before_reset", "重置前"),
    AFTER_RESET("after_reset", "重置后"),
    BEFORE_TERMINATE("before_terminate", "终止前"),
    AFTER_TERMINATE("after_terminate", "终止后");

    private final String code;
    private final String title;

    WorkflowRuntimePluginEventType(String code, String title) {
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
