package net.ximatai.muyun.spring.iam.user;

import java.time.Duration;

/**
 * Account lockout after consecutive credential failures. A locked account rejects every credential
 * attempt until the lock expires, and the attempt outcome stays indistinguishable from a bad password.
 * Applications configure it through {@code muyun.iam.login.lockout.*}; it is not a bean replacement
 * extension point.
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
