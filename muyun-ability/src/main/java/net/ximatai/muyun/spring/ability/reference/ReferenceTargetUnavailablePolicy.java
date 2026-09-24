package net.ximatai.muyun.spring.ability.reference;

/**
 * Defines deletion behavior for active referrers. Disabling a target does not delete it
 * or invoke this policy; enabled-state write requirements are declared separately.
 */
public enum ReferenceTargetUnavailablePolicy {
    PRESERVE_HISTORY,
    RESTRICT,
    CASCADE_DELETE
}
