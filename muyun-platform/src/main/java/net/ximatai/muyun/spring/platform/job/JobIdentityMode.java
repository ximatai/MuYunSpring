package net.ximatai.muyun.spring.platform.job;

/** Stable persisted identity modes. SERVICE_ACCOUNT is reserved and is rejected at execution. */
public enum JobIdentityMode {
    SYSTEM,
    INITIATOR,
    SERVICE_ACCOUNT
}
