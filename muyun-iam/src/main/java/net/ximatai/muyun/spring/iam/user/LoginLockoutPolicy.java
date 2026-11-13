package net.ximatai.muyun.spring.iam.user;

import java.time.Duration;

/**
 * Account lockout after consecutive credential failures. A locked account rejects every credential
 * attempt until the lock expires. Password login may disclose the lock deadline and near-limit
 * remaining attempts, without evaluating credentials during the lock.
 * Runtime values come exclusively from the persisted platform or tenant password security policy.
 * The constants below are used only to initialize the platform database record.
 */
public interface LoginLockoutPolicy {
    int DEFAULT_MAX_FAILED_ATTEMPTS = 5;
    Duration DEFAULT_LOCK_DURATION = Duration.ofMinutes(15);

    /** Consecutive failures that lock the account; zero or less disables lockout. */
    int maxFailedAttempts();

    Duration lockDuration();

    default boolean enabled() {
        return maxFailedAttempts() > 0;
    }

    static LoginLockoutPolicy defaults() {
        return of(DEFAULT_MAX_FAILED_ATTEMPTS, DEFAULT_LOCK_DURATION);
    }

    static LoginLockoutPolicy of(int maxFailedAttempts, Duration lockDuration) {
        if (maxFailedAttempts > 0 && (lockDuration == null || lockDuration.isNegative() || lockDuration.isZero())) {
            throw new IllegalArgumentException("lockDuration must be positive when lockout is enabled");
        }
        return new LoginLockoutPolicy() {
            @Override
            public int maxFailedAttempts() {
                return maxFailedAttempts;
            }

            @Override
            public Duration lockDuration() {
                return lockDuration;
            }
        };
    }
}
