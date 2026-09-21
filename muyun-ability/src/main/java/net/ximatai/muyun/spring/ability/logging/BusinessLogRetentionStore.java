package net.ximatai.muyun.spring.ability.logging;

/**
 * Storage-neutral destructive maintenance boundary for business logs.
 *
 * <p>Implementations must make concurrent retention runs safe and remove only events whose
 * occurrence time is strictly before the requested cutoff.</p>
 */
public interface BusinessLogRetentionStore {
    BusinessLogRetentionResult purge(BusinessLogRetentionRequest request);
}
