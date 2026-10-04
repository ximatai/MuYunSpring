package net.ximatai.muyun.spring.platform.metadata;

import java.util.Optional;

/** Declares one configuration reference fact consumed by deletion governance. */
public interface ConfigurationReferenceContributor {
    ConfigurationReferenceTarget target();

    ConfigurationReference reference();

    /** Human-readable identity supplied by the domain that owns the referenced configuration. */
    default String describeReference(String referenceId) {
        return reference().resourceName();
    }

    /** Whether changing a referenced field's identity, type or enabled state invalidates this configuration. */
    default boolean protectsFieldEvolution() { return false; }

    Optional<String> findReferenceId(String targetId);
}
