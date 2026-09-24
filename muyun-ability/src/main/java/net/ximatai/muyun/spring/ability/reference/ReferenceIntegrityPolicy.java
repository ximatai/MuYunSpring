package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.common.exception.PlatformException;

/** Resolved reference lifecycle policy shared by static and dynamic models. */
public record ReferenceIntegrityPolicy(
        ReferenceTargetUnavailablePolicy onTargetUnavailable,
        boolean requireEnabled
) {
    public static final ReferenceIntegrityPolicy DEFAULT = new ReferenceIntegrityPolicy(
            ReferenceTargetUnavailablePolicy.PRESERVE_HISTORY);

    public ReferenceIntegrityPolicy(ReferenceTargetUnavailablePolicy onTargetUnavailable) {
        this(onTargetUnavailable, false);
    }

    public ReferenceIntegrityPolicy {
        onTargetUnavailable = onTargetUnavailable == null
                ? ReferenceTargetUnavailablePolicy.PRESERVE_HISTORY
                : onTargetUnavailable;
    }

    public static ReferenceIntegrityPolicy from(ReferenceIntegrity integrity) {
        if (integrity == null) {
            return DEFAULT;
        }
        return new ReferenceIntegrityPolicy(integrity.onTargetUnavailable(), integrity.requireEnabled());
    }

    public void validateTarget(ReferenceTarget target, boolean supportsEnabledState) {
        if (requireEnabled && !supportsEnabledState) {
            throw new PlatformException("requireEnabled reference requires target ENABLE capability: "
                    + target.qualifiedName());
        }
    }
}
