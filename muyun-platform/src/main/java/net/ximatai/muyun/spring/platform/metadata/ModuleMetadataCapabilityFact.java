package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.platform.EntityCapability;

import java.util.List;

/** A capability's effective state, rather than a separate persisted toggle. */
public record ModuleMetadataCapabilityFact(
        EntityCapability capability,
        boolean enabled,
        boolean configurable,
        String reason,
        List<String> fieldContributions,
        String defaultKind,
        String defaultDescription
) {
}
