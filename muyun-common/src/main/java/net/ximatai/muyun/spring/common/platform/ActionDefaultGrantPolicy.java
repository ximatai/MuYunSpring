package net.ximatai.muyun.spring.common.platform;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

public enum ActionDefaultGrantPolicy implements CodeTitleEnum {
    NONE("NONE", "不默认授予", false),
    ANY_LOGIN_USER("ANY_LOGIN_USER", "所有登录用户", true),
    OWNER("OWNER", "记录所有者", true),
    ASSIGNEE("ASSIGNEE", "办理人", true),
    MEMBER("MEMBER", "成员", true);

    private final String code;
    private final String title;
    private final boolean grantsAuthenticatedUser;

    ActionDefaultGrantPolicy(String code, String title, boolean grantsAuthenticatedUser) {
        this.code = code;
        this.title = title;
        this.grantsAuthenticatedUser = grantsAuthenticatedUser;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getTitle() {
        return title;
    }

    public boolean grantsAuthenticatedUser() {
        return grantsAuthenticatedUser;
    }

    public boolean requiresDataScope() {
        return this == OWNER || this == ASSIGNEE || this == MEMBER;
    }
}
