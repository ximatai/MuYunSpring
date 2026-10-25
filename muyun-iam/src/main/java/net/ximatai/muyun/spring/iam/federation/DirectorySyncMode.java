package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/** Persisted synchronization modes, shared by jobs and read-only management projections. */
public enum DirectorySyncMode implements CodeTitleEnum {
    PREVIEW("预览"), SCOPE_PREVIEW("范围变更预览"), SYNC("正式同步");
    private final String title;
    DirectorySyncMode(String title) { this.title = title; }
    public String getCode() { return name(); }
    public String getTitle() { return title; }
}
