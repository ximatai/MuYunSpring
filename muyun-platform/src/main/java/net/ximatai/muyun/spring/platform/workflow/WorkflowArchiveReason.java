package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

public enum WorkflowArchiveReason implements CodeTitleEnum {
    RECALLED("recalled", "已撤回"),
    RESET("reset", "已重置"),
    RESTARTED("restarted", "驳回后重新发起");

    private final String code;
    private final String title;

    WorkflowArchiveReason(String code, String title) {
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
