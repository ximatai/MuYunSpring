package net.ximatai.muyun.spring.common.platform;

/** Persistent platform-owned gate for actions executed by a background job system principal. */
public interface JobSystemActionPolicy {
    String DECISION = "JOB_SYSTEM_WHITELIST_GRANTED";
    boolean allows(String moduleAlias, String actionCode);
}
