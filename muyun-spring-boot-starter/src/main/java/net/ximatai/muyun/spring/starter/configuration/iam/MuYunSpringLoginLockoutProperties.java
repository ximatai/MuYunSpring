package net.ximatai.muyun.spring.starter.configuration.iam;

import net.ximatai.muyun.spring.iam.user.LoginLockoutPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 连续登录失败锁定策略；{@code max-failed-attempts} 设为 0 表示关闭锁定。 */
@ConfigurationProperties("muyun.iam.login.lockout")
public class MuYunSpringLoginLockoutProperties implements LoginLockoutPolicy {
    private int maxFailedAttempts = DEFAULT_MAX_FAILED_ATTEMPTS;
    private Duration lockDuration = DEFAULT_LOCK_DURATION;

    public int getMaxFailedAttempts() {
        return maxFailedAttempts;
    }

    public void setMaxFailedAttempts(int maxFailedAttempts) {
        if (maxFailedAttempts < 0) {
            throw new IllegalArgumentException("muyun.iam.login.lockout.max-failed-attempts must not be negative");
        }
        this.maxFailedAttempts = maxFailedAttempts;
    }

    public Duration getLockDuration() {
        return lockDuration;
    }

    public void setLockDuration(Duration lockDuration) {
        // 启动期尽早拒绝无效时长，避免锁定开启后账号被即时解锁或永久锁定。
        if (lockDuration == null || lockDuration.isNegative() || lockDuration.isZero()) {
            throw new IllegalArgumentException("muyun.iam.login.lockout.lock-duration must be positive");
        }
        this.lockDuration = lockDuration;
    }

    @Override
    public int maxFailedAttempts() {
        return maxFailedAttempts;
    }

    @Override
    public Duration lockDuration() {
        return lockDuration;
    }
}
