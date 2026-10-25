package net.ximatai.muyun.spring.platform.job;

/** Enforces administrator authority over the requested job scope. */
public interface JobManagementAccess {
    void requireAdministrator(String tenantId);
}
