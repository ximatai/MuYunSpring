package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.spring.ability.logging.LoginLogEvent;

/** IAM-facing sink for completed login facts. Implementations must never alter authentication flow. */
@FunctionalInterface
public interface LoginAuditLogger {
    LoginAuditLogger NOOP = event -> { };

    void record(LoginLogEvent event);
}
